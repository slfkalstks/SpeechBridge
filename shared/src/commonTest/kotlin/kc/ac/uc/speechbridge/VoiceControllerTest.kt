@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package kc.ac.uc.speechbridge

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class VoiceControllerTest {
    private fun audio()=PcmWave.encode(ShortArray(1600) { 1000 },16000)
    private class Device(val audio:ByteArray):AudioDevice {
        var permission: suspend ()->Boolean = {true}
        var captures=0; var plays=0; var cancelled=0
        var recording=CompletableDeferred<ByteArray>()
        var preview: (ByteArray) -> Unit = {}
        var playback=CompletableDeferred<Unit>()
        override suspend fun requestPermission()=permission()
        override suspend fun record(progress:(Float,Int)->Unit):ByteArray {captures++;progress(0.5f,1);return recording.await()}
        override suspend fun recordWithPreview(progress: (Float, Int) -> Unit, preview: (ByteArray) -> Unit): ByteArray { this.preview = preview; return record(progress) }
        override fun finishRecording(){recording.complete(audio)}
        override suspend fun play(wav:ByteArray){plays++;playback.await()}
        override fun cancel(){cancelled++}
    }
    private class Api:VoiceApi {
        var calls=0; var synthesis=mutableListOf<String>()
        var transcription:suspend ()->Transcription = {Transcription("음성 테스트", "ko")}
        override suspend fun transcribe(wav:ByteArray,language:String):Transcription {calls++;assertEquals("auto",language);return transcription()}
        override suspend fun synthesize(text:String,language:Language):ByteArray {synthesis+=text;return PcmWave.encode(ShortArray(2400){1000},24000)}
    }
    @Test fun liveResultsAppearBeforeFinishButOnlyTheFinalResultIsRecorded() = runTest {
        val device = Device(audio()); val api = Api(); val results = mutableListOf<Transcription>()
        val voice = VoiceController(backgroundScope, device, api, { results += it }, { "번역: ${it.text}" })
        voice.start(); runCurrent(); device.preview(audio()); runCurrent()
        assertEquals(VoicePhase.RECORDING, voice.state.value.phase)
        assertEquals("음성 테스트", voice.state.value.previewText)
        assertEquals("번역: 음성 테스트", voice.state.value.previewTranslation)
        assertEquals(Language.KO, voice.state.value.previewLanguage)
        assertTrue(results.isEmpty())
        voice.finish(); runCurrent()
        assertEquals(1, results.size); assertEquals(2, api.calls)
    }
    @Test fun targetChangeRejectsOldTranslationWithoutDiscardingAudioOrSource() = runTest {
        val device = Device(audio()); val old = CompletableDeferred<String>(); var target = "en"
        val voice = VoiceController(backgroundScope, device, Api(), {}, { if(target == "en") old.await() else "한국어 결과" })
        voice.start(); runCurrent(); device.preview(audio()); runCurrent()
        target = "ko"; voice.refreshPreviewTranslation(); old.complete("old English"); runCurrent()
        assertEquals(VoicePhase.RECORDING, voice.state.value.phase)
        assertEquals("음성 테스트", voice.state.value.previewText)
        assertEquals("한국어 결과", voice.state.value.previewTranslation)
        voice.cancel()
    }
    @Test fun failedPreviewDoesNotAbortCaptureOrFinalTranscription() = runTest {
        val device = Device(audio()); val api = Api().apply { transcription = { throw IllegalStateException("temporary") } }
        val results = mutableListOf<Transcription>()
        val voice = VoiceController(backgroundScope, device, api, { results += it })
        voice.start(); runCurrent(); device.preview(audio()); runCurrent()
        assertEquals(VoicePhase.RECORDING, voice.state.value.phase)
        assertNotNull(voice.state.value.previewMessage)
        api.transcription = { Transcription("최종", "ko") }
        voice.finish(); runCurrent(); assertEquals("최종", results.single().text)
    }
    @Test fun cancelledPreviewCannotReturnIntoAnotherScreen() = runTest {
        val device = Device(audio()); val response = CompletableDeferred<Transcription>()
        val api = Api().apply { transcription = { withContext(NonCancellable) { response.await() } } }
        val voice = VoiceController(backgroundScope, device, api, { fail("cancelled") })
        voice.start(); runCurrent(); device.preview(audio()); runCurrent()
        voice.cancel(); response.complete(Transcription("늦은 결과", "ja")); runCurrent()
        assertEquals(VoiceState(), voice.state.value)
    }
    @Test fun permissionDenialDoesNotStartCaptureOrUpload()=runTest {
        val device=Device(audio()).apply {permission={false}}; val api=Api()
        val voice=VoiceController(backgroundScope,device,api,{fail("No transcript expected")})
        voice.start();runCurrent()
        assertEquals(VoicePhase.ERROR,voice.state.value.phase);assertEquals("microphone_denied",voice.state.value.errorCode)
        assertEquals(0,device.captures);assertEquals(0,api.calls)
    }
    @Test fun latePermissionAfterCancellationNeverStartsTheMicrophone()=runTest {
        val granted=CompletableDeferred<Boolean>(); val device=Device(audio()).apply {permission={withContext(NonCancellable){granted.await()}}}
        val voice=VoiceController(backgroundScope,device,Api(),{})
        voice.start();runCurrent();voice.cancel();granted.complete(true);runCurrent()
        assertEquals(0,device.captures);assertEquals(VoicePhase.IDLE,voice.state.value.phase)
    }
    @Test fun finishedRecordingAutomaticallyDeliversTheDetectedTranscriptExactlyOnce()=runTest {
        val device=Device(audio());val api=Api();val results=mutableListOf<Transcription>()
        val voice=VoiceController(backgroundScope,device,api,{results+=it})
        voice.start();runCurrent();assertEquals(VoicePhase.RECORDING,voice.state.value.phase)
        voice.finish();voice.finish();runCurrent()
        assertEquals(listOf(Transcription("음성 테스트","ko", audioDurationMs = 100)),results);assertEquals(1,api.calls)
        assertEquals(VoicePhase.IDLE,voice.state.value.phase)
    }
    @Test fun durationComesFromAudioAndExcludesTranscriptionWait() = runTest {
        val device = Device(PcmWave.encode(ShortArray(84000) { 1000 }, 16000))
        val api = Api().apply { transcription = { delay(10000); Transcription("결과", "ko", audioDurationMs = 999) } }
        val results = mutableListOf<Transcription>()
        val voice = VoiceController(backgroundScope, device, api, { results += it })
        voice.start(); runCurrent(); voice.finish(); runCurrent()
        assertEquals(6, voice.state.value.seconds)
        advanceTimeBy(10000); runCurrent()
        assertEquals(5250, results.single().audioDurationMs)
    }
    @Test fun cancelledTranscriptionCannotPublishALateResult()=runTest {
        val response=CompletableDeferred<Transcription>();val device=Device(audio());val api=Api().apply {transcription={withContext(NonCancellable){response.await()}}}
        val results=mutableListOf<Transcription>();val voice=VoiceController(backgroundScope,device,api,{results+=it})
        voice.start();runCurrent();voice.finish();runCurrent();assertEquals(VoicePhase.TRANSCRIBING,voice.state.value.phase)
        voice.cancel();response.complete(Transcription("늦은 결과","ko"));runCurrent()
        assertTrue(results.isEmpty());assertEquals(VoicePhase.IDLE,voice.state.value.phase)
    }
    @Test fun playbackDoesNotInterruptRecordingAndNewRecordingCancelsPlayback()=runTest {
        val device=Device(audio());val api=Api();val voice=VoiceController(backgroundScope,device,api,{})
        voice.start();runCurrent();voice.speak("재생 금지",Language.KO,"u1");runCurrent()
        assertTrue(api.synthesis.isEmpty());assertEquals(VoicePhase.RECORDING,voice.state.value.phase)
        voice.cancel();voice.speak("번역 읽기",Language.KO,"u1");runCurrent()
        assertEquals(VoicePhase.PLAYING,voice.state.value.phase);assertEquals(1,device.plays)
        voice.start();runCurrent();assertEquals(VoicePhase.RECORDING,voice.state.value.phase)
        assertEquals(2,device.captures)
        voice.cancel()
    }
    @Test fun stalledCaptureReturnsRecoverableErrorInsteadOfRemainingInRecording() = runTest {
        val voice = VoiceController(backgroundScope,Device(audio()),Api(),{})
        voice.start(); runCurrent(); advanceTimeBy(32_001); runCurrent()
        assertEquals(VoicePhase.ERROR,voice.state.value.phase)
        assertEquals("audio_timeout",voice.state.value.errorCode)
    }
    @Test fun activityRecreationReplacesNativeDeviceAndRejectsItsLatePermission() = runTest {
        val granted = CompletableDeferred<Boolean>()
        val old = Device(audio()).apply { permission = { withContext(NonCancellable) { granted.await() } } }
        val next = Device(audio())
        val results = mutableListOf<Transcription>()
        val voice = VoiceController(backgroundScope,old,Api(),{ results += it })
        voice.start(); runCurrent(); voice.attachDevice(next)
        voice.start(); runCurrent(); granted.complete(true); runCurrent()
        assertEquals(0,old.captures); assertEquals(1,next.captures)
        voice.finish(); runCurrent(); assertEquals(1,results.size)
    }
    @Test fun longTranslationsAreReadInBoundedChunksWithoutSplittingSurrogates()=runTest {
        val text="긴 번역 😀 ".repeat(70)
        val chunks=voiceChunks(text)
        assertTrue(chunks.size>1);assertEquals(text.trim(),chunks.joinToString(""))
        assertTrue(chunks.all {it.length<=300 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate()})
    }
    @Test fun wavCodecPreservesSamplesAndRejectsInvalidHeadersOrExcessDuration() {
        val samples=shortArrayOf(-32768,-1,0,1,32767)
        val wav=PcmWave.encode(samples,16000)
        assertEquals(54,wav.size);assertContentEquals(samples,PcmWave.decode(wav,16000).samples)
        assertFails {PcmWave.decode(wav,24000)}
        assertFails {PcmWave.decode(wav+byteArrayOf(0),16000)}
        assertFails {PcmWave.encode(ShortArray(480001),16000)}
    }
}
