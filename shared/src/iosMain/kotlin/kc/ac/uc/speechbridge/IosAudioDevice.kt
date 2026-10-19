@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kc.ac.uc.speechbridge

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.AVFAudio.*
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.Foundation.*
import kotlin.coroutines.resume
import kotlin.math.sqrt
import kotlin.math.ceil
import kotlinx.coroutines.channels.Channel

/** Foreground AVAudioEngine capture. Raw audio stays in memory and supports live previews. */
class IosAudioDevice : AudioDevice {
    private var finish = false
    private var cancelled = false
    private var interrupted = false
    private var engine: AVAudioEngine? = null
    private var player: AVAudioPlayer? = null
    private val session = AVAudioSession.sharedInstance()
    private val interruption = NSNotificationCenter.defaultCenter.addObserverForName(
        AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { interrupted = true }

    override suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        AVAudioApplication.requestRecordPermissionWithCompletionHandler { granted ->
            if(continuation.isActive) continuation.resume(granted)
        }
    }
    override suspend fun record(progress: (Float, Int) -> Unit): ByteArray = recordWithPreview(progress, {})
    override suspend fun recordWithPreview(progress: (Float, Int) -> Unit, preview: (ByteArray) -> Unit): ByteArray {
        finish = false; cancelled = false; interrupted = false
        val capture = AVAudioEngine()
        engine = capture
        val chunks = Channel<ShortArray>(64)
        var tapped = false
        try {
            captureReady(session.setCategory(AVAudioSessionCategoryPlayAndRecord, AVAudioSessionCategoryOptionDefaultToSpeaker, null))
            captureReady(session.setPreferredSampleRate(16000.0, null))
            captureReady(session.setActive(true, null))
            val input = capture.inputNode
            val format = input.outputFormatForBus(0u)
            if (format.sampleRate <= 0 || format.channelCount == 0u)
                throw BridgeRequestException("audio_input_failed", "마이크 입력 장치를 확인해 주세요.")
            val converter = IosPcmConverter(format)
            input.installTapOnBus(0u, 4096u, format) { buffer, _ ->
                if (buffer != null) {
                    try {
                        val pcm = converter.convert(buffer)
                        if (pcm.isNotEmpty() && chunks.trySend(pcm).isFailure)
                            chunks.close(BridgeRequestException("audio_input_failed", "음성 입력이 지연되었습니다. 다시 시도해 주세요."))
                    } catch (_: Exception) {
                        chunks.close(BridgeRequestException("audio_format_invalid", "마이크 입력 형식을 변환하지 못했습니다."))
                    }
                }
            }
            tapped = true
            capture.prepare()
            captureReady(capture.startAndReturnError(null))
            val all = ShortArray(480000)
            var count = 0
            var nextPreview = 48000
            val started = kotlin.time.TimeSource.Monotonic.markNow()
            while (!finish && !cancelled && count < all.size && started.elapsedNow().inWholeMilliseconds < 30000) {
                currentCoroutineContext().ensureActive()
                if(interrupted) throw BridgeRequestException("audio_interrupted", "음성 입력이 중단되었습니다. 다시 시작해 주세요.")
                val chunk = withTimeoutOrNull(100) { chunks.receive() } ?: continue
                val size = minOf(chunk.size, all.size - count)
                chunk.copyInto(all, count, 0, size); count += size
                val rms = sqrt(chunk.sumOf { it.toDouble() * it } / chunk.size) / 32768
                progress((rms * 5).toFloat(), count / 16000)
                if (count >= nextPreview) {
                    preview(PcmWave.encode(all.copyOf(count), 16000))
                    nextPreview = count + 48000
                }
            }
            currentCoroutineContext().ensureActive()
            if(cancelled) throw CancellationException("Audio cancelled")
            if(count < 1600) throw BridgeRequestException("no_speech", "녹음된 소리가 너무 짧습니다. 입력 장치를 확인하고 조금 더 길게 말해 주세요.")
            return PcmWave.encode(all.copyOf(count), 16000)
        } finally {
            capture.stop()
            if(tapped) capture.inputNode.removeTapOnBus(0u)
            chunks.close(); engine = null
            session.setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
        }
    }
    override fun finishRecording() { finish = true }
    override suspend fun play(wav: ByteArray) {
        PcmWave.decode(wav,24000)
        cancelled = false; interrupted = false
        try {
            check(session.setCategory(AVAudioSessionCategoryPlayback,null))
            check(session.setActive(true,null))
            val data = wav.usePinned { NSData.create(bytes = it.addressOf(0), length = wav.size.toULong()) }
            val output = AVAudioPlayer(data,null)
            player = output
            check(output.prepareToPlay()); check(output.play())
            withTimeout(33_000) {
                while(output.playing && !cancelled && !interrupted) { ensureActive(); delay(50) }
            }
            currentCoroutineContext().ensureActive()
            if(cancelled) throw CancellationException("Audio cancelled")
            if(interrupted) throw BridgeRequestException("audio_interrupted", "음성 재생이 중단되었습니다.")
        } finally {
            player?.stop(); player = null
            session.setActive(false,AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,null)
        }
    }
    override fun cancel() { cancelled = true; engine?.stop(); player?.stop() }
    override fun close() { cancel(); NSNotificationCenter.defaultCenter.removeObserver(interruption) }
}

/** Normalize only the completed temporary capture before upload. */
internal fun readIosRecording(file: NSURL): ByteArray {
    // Let AVFoundation decode its own container; only canonical PCM leaves the device.
    val input = try {
        AVAudioFile(forReading = file, commonFormat = AVAudioPCMFormatInt16, interleaved = true, error = null)
    } catch (_: Exception) {
        throw BridgeRequestException("audio_read_failed", "녹음 파일을 읽지 못했습니다. 입력 장치를 확인하고 다시 녹음해 주세요.")
    }
    try {
        val format = input.processingFormat
        if (format.sampleRate != 16000.0 || format.channelCount != 1u)
            throw BridgeRequestException("audio_format_invalid", "녹음 형식을 확인할 수 없습니다. 입력 장치를 다시 연결해 주세요.")
        val frames = input.length
        if (frames < 1600) throw BridgeRequestException("no_speech", "녹음된 소리가 너무 짧습니다. 입력 장치를 확인하고 조금 더 길게 말해 주세요.")
        if (frames > 480000) throw BridgeRequestException("audio_too_long", "녹음이 30초를 넘었습니다. 짧게 나누어 말해 주세요.")
        val buffer = AVAudioPCMBuffer(format, frames.toUInt())
        if (!input.readIntoBuffer(buffer, null) || buffer.frameLength.toLong() != frames)
            throw BridgeRequestException("audio_read_failed", "녹음 파일을 끝까지 읽지 못했습니다. 다시 녹음해 주세요.")
        val channel = buffer.int16ChannelData?.get(0)
            ?: throw BridgeRequestException("audio_read_failed", "녹음된 소리가 없습니다. 입력 장치를 확인해 주세요.")
        return PcmWave.encode(ShortArray(frames.toInt()) { channel[it] }, 16000)
    } finally { input.close() }
}

private fun captureReady(success: Boolean) {
    if (!success) throw BridgeRequestException("audio_input_failed", "마이크 입력을 시작하지 못했습니다. 입력 장치와 마이크 권한을 확인해 주세요.")
}


/** AVAudioConverter handles hardware sample rates and channel layouts before upload. */
internal class IosPcmConverter(format: AVAudioFormat) {
    private val output = AVAudioFormat(AVAudioPCMFormatInt16, 16000.0, 1u, true)
    private val converter = AVAudioConverter(format, output)
    fun convert(input: AVAudioPCMBuffer): ShortArray {
        val capacity = ceil(input.frameLength.toDouble() * 16000.0 / input.format.sampleRate).toUInt() + 256u
        val buffer = AVAudioPCMBuffer(output, capacity)
        var supplied = false
        val status = converter.convertToBuffer(buffer, null) { _, state ->
            if (supplied) {
                state?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
                null
            } else {
                supplied = true
                state?.pointed?.value = AVAudioConverterInputStatus_HaveData
                input
            }
        }
        check(status != AVAudioConverterOutputStatus_Error)
        val channel = buffer.int16ChannelData?.get(0) ?: return ShortArray(0)
        return ShortArray(buffer.frameLength.toInt()) { channel[it] }
    }
}
