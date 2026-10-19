@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package kc.ac.uc.speechbridge

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class VoiceWorkspaceTest {
    private class Device: AudioDevice {
        override suspend fun requestPermission() = true
        override suspend fun record(progress: (Float, Int) -> Unit) = PcmWave.encode(ShortArray(1600) { 1000 }, 16000)
        override fun finishRecording() {}
        override suspend fun play(wav: ByteArray) {}
        override fun cancel() {}
    }
    private class Voice: VoiceApi {
        val spoken = mutableListOf<String>()
        var result = CompletableDeferred<Transcription>()
        override suspend fun transcribe(wav: ByteArray, language: String) = withContext(NonCancellable) { result.await() }
        override suspend fun synthesize(text: String, language: Language): ByteArray { spoken += text; return PcmWave.encode(ShortArray(2400) { 1000 }, 24000) }
    }
    private class Api: BridgeApi {
        val inputs = mutableListOf<TranslationRequest>()
        override suspend fun translate(connection: Connection, request: TranslationRequest): TranslationResponse {
            inputs += request; return TranslationResponse("번역 결과", request.revision)
        }
        override suspend fun extract(connection: Connection, request: ExtractionRequest) = ExtractionResponse(emptyList())
        override suspend fun health(connection: Connection) = Health("ok", emptyMap())
    }
    @Test fun automaticDirectionRecordsOnceAndPreservesUnsentKeyboardDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore(); val voice = Voice(); val api = Api(); val store = MemorySessionStore()
        val vm = BridgeViewModel(store, api, audioDevice = Device(), voiceApi = voice)
        owner.put("vm", vm)
        try {
            runCurrent(); vm.updateIdleDraft(UtteranceDraft(text = "아직 보내지 않은 입력"))
            vm.startVoice(); runCurrent(); voice.result.complete(Transcription("Hello", "en")); runCurrent()
            val c = vm.state.value.selected!!
            assertEquals(Language.EN, c.first); assertEquals(Language.KO, c.second)
            assertEquals("Hello", c.utterances.single().text); assertEquals("", c.utterances.single().speaker)
            assertEquals("en", api.inputs.single().from); assertEquals("ko", api.inputs.single().to)
            assertEquals("아직 보내지 않은 입력", vm.state.value.inputDrafts[c.id]?.text)
            assertEquals("Hello", ArchiveCodec.decode(store.read()!!).single().utterances.single().text)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun leavingConversationDiscardsLateSpeechWithoutCreatingARecord() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore(); val voice = Voice()
        val vm = BridgeViewModel(MemorySessionStore(), Api(), audioDevice = Device(), voiceApi = voice)
        owner.put("vm", vm)
        try {
            runCurrent(); vm.startVoice(); runCurrent(); vm.navigate(Page.HISTORY)
            voice.result.complete(Transcription("늦은 결과", "ko")); runCurrent()
            assertTrue(vm.state.value.conversations.isEmpty()); assertEquals(VoicePhase.IDLE, vm.voice.state.value.phase)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun newTranslationReadsAutomaticallyButOptOutSurvivesRestart() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore(); val voice = Voice(); val store = MemorySessionStore()
        val vm = BridgeViewModel(store, Api(), audioDevice = Device(), voiceApi = voice)
        owner.put("vm", vm)
        try {
            runCurrent(); vm.startConversation(); val id = vm.state.value.activeId!!
            vm.addUtterance(id, Language.KO, "안녕하세요."); runCurrent()
            assertEquals(listOf("번역 결과"), voice.spoken)
            vm.setAutoReadTranslation(false); runCurrent()
            vm.addUtterance(id, Language.KO, "다음 문장입니다."); runCurrent()
            assertEquals(1, voice.spoken.size)
            assertFalse(ArchiveCodec.decodeArchive(store.read()!!).autoReadTranslation)
            val restored = BridgeViewModel(store, Api(), audioDevice = Device(), voiceApi = voice)
            owner.put("restored", restored); runCurrent()
            assertFalse(restored.state.value.autoReadTranslation)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

}
