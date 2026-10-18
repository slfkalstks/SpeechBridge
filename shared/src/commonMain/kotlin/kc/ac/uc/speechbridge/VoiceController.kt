package kc.ac.uc.speechbridge

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

interface AudioDevice {
    suspend fun requestPermission(): Boolean
    /** Mono PCM16/16kHz WAV, at most 30 seconds. No permanent recording file. */
    suspend fun record(progress: (Float, Int) -> Unit): ByteArray
    fun finishRecording()
    suspend fun play(wav: ByteArray)
    fun cancel()
    fun close() = cancel()
}
class UnavailableAudioDevice : AudioDevice {
    override suspend fun requestPermission(): Boolean = throw BridgeRequestException("audio_unavailable", "이 환경에서는 마이크를 사용할 수 없습니다.")
    override suspend fun record(progress: (Float, Int) -> Unit): ByteArray = error("No audio device")
    override fun finishRecording() {}
    override suspend fun play(wav: ByteArray) { error("No audio device") }
    override fun cancel() {}
}
@Serializable data class Transcription(val text: String, val language: String)
interface VoiceApi {
    suspend fun transcribe(wav: ByteArray, language: String = "auto"): Transcription
    suspend fun synthesize(text: String, language: Language): ByteArray
    fun close() {}
}
enum class VoicePhase { IDLE, PERMISSION, RECORDING, TRANSCRIBING, SYNTHESIZING, PLAYING, ERROR }
data class VoiceState(val phase: VoicePhase = VoicePhase.IDLE, val level: Float = 0f, val seconds: Int = 0,
    val playingKey: String? = null, val errorCode: String? = null, val message: String? = null)

/** Owns one foreground capture or playback. Generation guards include late permission callbacks. */
class VoiceController(private val scope: CoroutineScope, private var device: AudioDevice, private val api: VoiceApi,
    private val onTranscript: (Transcription) -> Unit) {
    private val mutable = MutableStateFlow(VoiceState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private fun begin(initial: VoiceState, operation: suspend (Long) -> Unit) {
        val previous = job
        cancel()
        val epoch = generation
        mutable.value = initial
        job = scope.launch {
            try {
                previous?.join()
                ensureActive()
                operation(epoch)
            } catch (e: TimeoutCancellationException) {
                if (generation == epoch) mutable.value = VoiceState(VoicePhase.ERROR, errorCode = "audio_timeout",
                    message = "음성 처리 시간이 초과되었습니다. 다시 시도해 주세요.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == epoch) mutable.value = VoiceState(VoicePhase.ERROR,
                    errorCode = (e as? BridgeRequestException)?.code ?: "audio_failed",
                    message = if(e is BridgeRequestException) e.message else "음성을 처리하지 못했습니다. 다시 시도해 주세요.")
            } finally { if(generation == epoch) device.cancel() }
        }
    }
    fun start(language: String = "auto") = begin(VoiceState(VoicePhase.PERMISSION)) { epoch ->
        val granted = device.requestPermission()
        currentCoroutineContext().ensureActive()
        if (epoch != generation) return@begin
        if (!granted) throw BridgeRequestException("microphone_denied", "마이크 권한이 필요합니다. 기기 설정에서 SpeechBridge의 마이크를 허용해 주세요.")
        mutable.value = VoiceState(VoicePhase.RECORDING)
        val wav = withTimeout(32_000) {
            device.record { level, seconds -> scope.launch {
                if (epoch == generation && state.value.phase == VoicePhase.RECORDING)
                    mutable.update { it.copy(level = level.coerceIn(0f,1f), seconds = seconds.coerceIn(0,30)) }
            } }
        }
        currentCoroutineContext().ensureActive()
        if (epoch != generation) return@begin
        PcmWave.decode(wav,16000)
        mutable.value = VoiceState(VoicePhase.TRANSCRIBING)
        val result = api.transcribe(wav, language)
        currentCoroutineContext().ensureActive()
        require(result.text.isNotBlank() && result.text.length <= 16000 && Language.entries.any { it.code == result.language })
        if (epoch == generation) {
            mutable.value = VoiceState()
            onTranscript(result)
        }
    }
    fun finish() { if(state.value.phase == VoicePhase.RECORDING) device.finishRecording() }
    fun speak(text: String, language: Language, key: String) {
        if(state.value.phase in setOf(VoicePhase.PERMISSION, VoicePhase.RECORDING, VoicePhase.TRANSCRIBING)) return
        begin(VoiceState(VoicePhase.SYNTHESIZING, playingKey = key)) { epoch ->
            for(chunk in voiceChunks(text)) {
                mutable.value = VoiceState(VoicePhase.SYNTHESIZING, playingKey = key)
                val wav = api.synthesize(chunk, language)
                currentCoroutineContext().ensureActive()
                if (epoch != generation) return@begin
                PcmWave.decode(wav,24000)
                mutable.value = VoiceState(VoicePhase.PLAYING, playingKey = key)
                device.play(wav)
                currentCoroutineContext().ensureActive()
            }
            if(epoch == generation) mutable.value = VoiceState()
        }
    }
    fun cancel() { generation++; job?.cancel(); device.cancel(); mutable.value = VoiceState() }
    fun attachDevice(next: AudioDevice) {
        if(device === next) return
        cancel(); device.close(); device = next
    }
    fun close() { cancel(); device.close(); api.close() }
}

/** Preserve every character and split only at UTF-16 boundaries safe for Unicode scalars. */
fun voiceChunks(text: String): List<String> {
    var remaining = text.trim()
    require(remaining.isNotEmpty())
    val chunks = mutableListOf<String>()
    while(remaining.isNotEmpty()) {
        var end = minOf(280, remaining.length)
        if(remaining[end-1].isHighSurrogate()) end--
        if(end < remaining.length) {
            val boundary = remaining.take(end).indexOfLast { it.isWhitespace() || it in ".!?。！？" }
            if(boundary > end/2) end = boundary+1
        }
        chunks += remaining.substring(0,end)
        remaining = remaining.substring(end)
    }
    return chunks
}
