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
        val folder = directory()
        // Only this adapter's temporary files; conversation archives are elsewhere.
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(folder.path!!, null)?.filterIsInstance<String>()
            ?.filter { it.startsWith("capture-") && it.endsWith(".wav") }?.forEach {
                NSFileManager.defaultManager.removeItemAtURL(folder.URLByAppendingPathComponent(it)!!, null)
            }
        val file = folder.URLByAppendingPathComponent("capture-${NSUUID().UUIDString}.wav")!!
        try {
            check(session.setCategory(AVAudioSessionCategoryPlayAndRecord, AVAudioSessionCategoryOptionDefaultToSpeaker, null))
            check(session.setPreferredSampleRate(16000.0, null))
            check(session.setActive(true, null))
            val input = AVAudioRecorder(file, mapOf<Any?,Any>(
                AVFormatIDKey to kAudioFormatLinearPCM, AVSampleRateKey to 16000.0, AVNumberOfChannelsKey to 1,
                AVLinearPCMBitDepthKey to 16, AVLinearPCMIsBigEndianKey to false, AVLinearPCMIsFloatKey to false), null)
            recorder = input
            input.meteringEnabled = true
            check(input.prepareToRecord())
            check(NSFileManager.defaultManager.setAttributes(mapOf(NSFileProtectionKey to NSFileProtectionComplete), file.path!!, null))
            check(input.recordForDuration(30.0))
            val started = NSDate.timeIntervalSinceReferenceDate
            while(input.recording && !finish && !cancelled && NSDate.timeIntervalSinceReferenceDate-started < 30.1) {
                currentCoroutineContext().ensureActive()
                if(interrupted) throw BridgeRequestException("audio_interrupted", "음성 입력이 중단되었습니다. 다시 시작해 주세요.")
                input.updateMeters()
                progress((10.0.pow(input.averagePowerForChannel(0u)/20.0)*5).toFloat(),input.currentTime.toInt())
                delay(50)
            }
            input.stop()
            currentCoroutineContext().ensureActive()
            if(cancelled) throw CancellationException("Audio cancelled")
            if(interrupted) throw BridgeRequestException("audio_interrupted", "음성 입력이 중단되었습니다. 다시 시작해 주세요.")
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
    val data = NSData.dataWithContentsOfURL(file) ?: error("Audio read failed")
    require(data.length <= 1_025_580u)
    val samples = PcmWave.decode(data.bytes!!.readBytes(data.length.toInt()),16000,allowMetadata = true).samples
    if(samples.size < 1600) throw BridgeRequestException("no_speech", "조금 더 길게 말한 뒤 완료를 눌러 주세요.")
    return PcmWave.encode(samples,16000)
}
