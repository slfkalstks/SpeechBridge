package kc.ac.uc.speechbridge

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.SystemClock
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Foreground-only PCM capture and playback. Raw audio stays in process memory. */
class AndroidAudioDevice(context: Context, private val permission: suspend () -> Boolean) : AudioDevice {
    private val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val finish = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val interrupted = AtomicBoolean(false)
    override suspend fun requestPermission() = permission()
    private fun focus(): AudioFocusRequest {
        interrupted.set(false)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { if(it < 0) interrupted.set(true) }.build()
        if(manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw BridgeRequestException("audio_busy", "다른 앱에서 소리를 사용 중입니다. 잠시 후 다시 시도해 주세요.")
        return request
    }
    override suspend fun record(progress: (Float, Int) -> Unit): ByteArray = recordWithPreview(progress, {})
    @SuppressLint("MissingPermission")
    override suspend fun recordWithPreview(progress: (Float, Int) -> Unit, preview: (ByteArray) -> Unit): ByteArray {
        finish.set(false); cancelled.set(false)
        return withContext(Dispatchers.IO) {
            val audioFocus = focus()
            var recorder: AudioRecord? = null
            try {
                val size = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if(size <= 0) throw BridgeRequestException("audio_unavailable", "이 기기의 마이크 형식을 사용할 수 없습니다.")
                val input = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(size, 6400))
                recorder = input
                check(input.state == AudioRecord.STATE_INITIALIZED)
                input.startRecording()
                val all = ShortArray(16000*30); val buffer = ShortArray(1600)
                var count = 0
                var nextPreview = 16000 * 3
                val started = SystemClock.elapsedRealtime()
                while(!finish.get() && !cancelled.get() && count < all.size && SystemClock.elapsedRealtime()-started < 30_000) {
                    currentCoroutineContext().ensureActive()
                    if(interrupted.get()) throw BridgeRequestException("audio_interrupted", "음성 입력이 중단되었습니다. 다시 시작해 주세요.")
                    val read = input.read(buffer, 0, minOf(buffer.size,all.size-count), AudioRecord.READ_NON_BLOCKING)
                    check(read >= 0)
                    if(read > 0) {
                        buffer.copyInto(all,count,0,read); count += read
                        val rms = sqrt((0 until read).sumOf { buffer[it].toDouble()*buffer[it] } / read) / 32768
                        progress((rms*5).toFloat(), count/16000)
                        if(count >= nextPreview) {
                            preview(PcmWave.encode(all.copyOf(count), 16000))
                            nextPreview = count + 16000 * 3
                        }
                    }
                    delay(20)
                }
                currentCoroutineContext().ensureActive()
                if(cancelled.get()) throw CancellationException("Audio cancelled")
                if(count < 1600) throw BridgeRequestException("no_speech", "조금 더 길게 말한 뒤 완료를 눌러 주세요.")
                PcmWave.encode(all.copyOf(count),16000)
            } finally {
                recorder?.let { runCatching { it.stop() }; it.release() }
                manager.abandonAudioFocusRequest(audioFocus)
            }
        }
    }
    override fun finishRecording() { finish.set(true) }
    override suspend fun play(wav: ByteArray) {
        cancelled.set(false)
        val pcm = PcmWave.decode(wav,24000).samples
        withContext(Dispatchers.IO) {
            val audioFocus = focus()
            var track: AudioTrack? = null
            try {
                val min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(min > 0)
                val output = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(min,4800))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                track = output; check(output.state == AudioTrack.STATE_INITIALIZED); output.play()
                withTimeout(33_000) {
                    var written = 0
                    while(output.playbackHeadPosition < pcm.size) {
                        ensureActive()
                        if(cancelled.get()) throw CancellationException("Audio cancelled")
                        if(interrupted.get()) throw BridgeRequestException("audio_interrupted", "음성 재생이 중단되었습니다.")
                        if(written < pcm.size) {
                            val count = output.write(pcm,written,minOf(2400,pcm.size-written),AudioTrack.WRITE_NON_BLOCKING)
                            check(count >= 0); written += count
                        }
                        delay(20)
                    }
                }
            } finally {
                track?.let { runCatching { it.stop() }; it.release() }
                manager.abandonAudioFocusRequest(audioFocus)
            }
        }
    }
    override fun cancel() { cancelled.set(true) }
}
