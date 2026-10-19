@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kc.ac.uc.speechbridge

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.AVFAudio.*
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.Foundation.*
import kotlin.coroutines.resume
import kotlin.math.pow

/** Main-thread AVFoundation adapter. Capture files are protected, temporary and never backed up. */
class IosAudioDevice : AudioDevice {
    private var finish = false
    private var cancelled = false
    private var interrupted = false
    private var recorder: AVAudioRecorder? = null
    private var player: AVAudioPlayer? = null
    private val session = AVAudioSession.sharedInstance()
    private val interruption = NSNotificationCenter.defaultCenter.addObserverForName(
        AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { interrupted = true }

    override suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        AVAudioApplication.requestRecordPermissionWithCompletionHandler { granted ->
            if(continuation.isActive) continuation.resume(granted)
        }
    }
    private fun directory(): NSURL {
        val root = NSFileManager.defaultManager.URLForDirectory(NSCachesDirectory, NSUserDomainMask, null, true, null)!!
        val folder = root.URLByAppendingPathComponent("SpeechBridgeVoice", true)!!
        check(NSFileManager.defaultManager.createDirectoryAtURL(folder, true,
            mapOf(NSFileProtectionKey to NSFileProtectionComplete), null))
        check(folder.setResourceValue(true, NSURLIsExcludedFromBackupKey, null))
        return folder
    }
    override suspend fun record(progress: (Float, Int) -> Unit): ByteArray {
        finish = false; cancelled = false; interrupted = false
        val folder = try { directory() } catch (_: Exception) {
            throw BridgeRequestException("audio_storage_failed", "임시 녹음 파일을 준비하지 못했습니다. 기기 저장 공간을 확인해 주세요.")
        }
        // Only this adapter's temporary files; conversation archives are elsewhere.
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(folder.path!!, null)?.filterIsInstance<String>()
            ?.filter { it.startsWith("capture-") && it.endsWith(".wav") }?.forEach {
                NSFileManager.defaultManager.removeItemAtURL(folder.URLByAppendingPathComponent(it)!!, null)
            }
        val file = folder.URLByAppendingPathComponent("capture-${NSUUID().UUIDString}.wav")!!
        try {
            captureReady(session.setCategory(AVAudioSessionCategoryPlayAndRecord, AVAudioSessionCategoryOptionDefaultToSpeaker, null))
            captureReady(session.setPreferredSampleRate(16000.0, null))
            captureReady(session.setActive(true, null))
            val input = try { AVAudioRecorder(file, mapOf<Any?,Any>(
                AVFormatIDKey to kAudioFormatLinearPCM, AVSampleRateKey to 16000.0, AVNumberOfChannelsKey to 1,
                AVLinearPCMBitDepthKey to 16, AVLinearPCMIsBigEndianKey to false, AVLinearPCMIsFloatKey to false), null) } catch (_: Exception) {
                throw BridgeRequestException("audio_input_failed", "녹음 장치를 준비하지 못했습니다. 마이크 연결을 확인해 주세요.")
            }
            recorder = input
            input.meteringEnabled = true
            captureReady(input.prepareToRecord())
            if (!NSFileManager.defaultManager.setAttributes(mapOf(NSFileProtectionKey to NSFileProtectionComplete), file.path!!, null))
                throw BridgeRequestException("audio_storage_failed", "녹음 파일을 보호하지 못했습니다. 다시 시작해 주세요.")
            captureReady(input.recordForDuration(30.0))
            val started = NSDate.timeIntervalSinceReferenceDate
            while(input.recording && !finish && !cancelled && NSDate.timeIntervalSinceReferenceDate-started < 30.1) {
                currentCoroutineContext().ensureActive()
                if(interrupted) throw BridgeRequestException("audio_interrupted", "음성 입력이 중단되었습니다. 다시 시작해 주세요.")
                input.updateMeters()
                progress((10.0.pow(input.averagePowerForChannel(0u)/20.0)*5).toFloat(),input.currentTime.toInt())
                delay(50)
            }
            val stoppedUnexpectedly = !input.recording && !finish && !cancelled &&
                NSDate.timeIntervalSinceReferenceDate-started < 29.5
            input.stop()
            currentCoroutineContext().ensureActive()
            if(cancelled) throw CancellationException("Audio cancelled")
            if(interrupted) throw BridgeRequestException("audio_interrupted", "음성 입력이 중단되었습니다. 다시 시작해 주세요.")
            if (stoppedUnexpectedly) throw BridgeRequestException("audio_input_failed",
                "녹음 도중 입력 장치가 중단되었습니다. 마이크 연결을 확인한 뒤 다시 녹음해 주세요.")
            return readIosRecording(file)
        } finally {
            recorder?.stop(); recorder = null
            NSFileManager.defaultManager.removeItemAtURL(file,null)
            session.setActive(false,AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,null)
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
    override fun cancel() { cancelled = true; recorder?.stop(); player?.stop() }
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
