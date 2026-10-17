@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package kc.ac.uc.speechbridge

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class BridgeViewModelTest {
    private class Store(var data: String? = null) : SessionStore {
        var writes = 0
        var failRead = false
        var failWrite = false
        override suspend fun read(): String? { if (failRead) error("read failed"); return data }
        override suspend fun write(text: String) { writes++; if (failWrite) error("disk full"); data = text }
    }
    private class Api : BridgeApi {
        var organization: CompletableDeferred<OrganizationResponse>? = null
        override suspend fun organize(connection: Connection, request: OrganizationRequest): OrganizationResponse {
            organizationCalls++
            return organization?.let { withContext(NonCancellable) { it.await() } } ?: throw BridgeRequestException("test_offline", "offline")
        }
        var organizationCalls = 0
        val translationInputs = mutableListOf<TranslationRequest>()
        val translations = mutableListOf<CompletableDeferred<TranslationResponse>>()
        val extractions = mutableListOf<CompletableDeferred<ExtractionResponse>>()
        val extractionInputs = mutableListOf<ExtractionRequest>()
        override suspend fun health(connection: Connection) = Health("ok", mapOf("translation" to false))
        override suspend fun translate(connection: Connection, request: TranslationRequest): TranslationResponse {
            translationInputs += request
            val result = CompletableDeferred<TranslationResponse>(); translations += result
            return withContext(NonCancellable) { result.await() }
        }
        override suspend fun extract(connection: Connection, request: ExtractionRequest): ExtractionResponse {
            extractionInputs += request
            val result = CompletableDeferred<ExtractionResponse>(); extractions += result
            return withContext(NonCancellable) { result.await() }
        }
    }
    private fun TestScope.withVm(store: Store = Store(), api: Api = Api(), block: TestScope.(BridgeViewModel, Store, Api) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        val vm = BridgeViewModel(store, api)
        owner.put("test", vm)
        try { runCurrent(); block(vm, store, api) }
        finally { owner.clear(); Dispatchers.resetMain() }
    }
    private fun BridgeViewModel.startRecorded() { start("회의", false, true, Language.KO, Language.EN) }

    @Test fun draftsSurviveNavigationButNeverReachArchiveAndAreClearedOnEnd() = runTest {
        withVm { vm, store, _ ->
            vm.startRecorded(); val id = vm.state.value.activeId!!
            vm.updateInputDraft(id, UtteranceDraft(text = "NOT-SAVED-DRAFT"))
            vm.updateConnectionDraft(Connection("https://example.test", "NOT-SAVED-TOKEN"))
            vm.updateStartDraft(StartDraft(title = "NOT-SAVED-START"))
            vm.navigate(Page.HISTORY); vm.navigate(Page.TALK); runCurrent()
            assertEquals("NOT-SAVED-DRAFT", vm.state.value.inputDrafts[id]?.text)
            assertFalse(store.data!!.contains("NOT-SAVED"))
            vm.end(id); runCurrent()
            assertTrue(vm.state.value.inputDrafts.isEmpty())
            assertFalse(store.data!!.contains("NOT-SAVED"))
        }
    }
    @Test fun committedInputClearsOnlyItsDraftAndExtractionUsesSelectedLanguage() = runTest {
        withVm { vm, _, api ->
            vm.start("日本語", false, true, Language.JA, Language.EN)
            val id = vm.state.value.activeId!!
            vm.updateInputDraft(id, UtteranceDraft(language = Language.JA, text = "見積書を確認してください。"))
            vm.addUtterance(id, "ユキ", Language.JA, "見積書を確認してください。")
            assertTrue(vm.state.value.inputDrafts.isEmpty())
            vm.extract(id); runCurrent()
            assertEquals("ja", api.extractionInputs.single().language)
            api.extractions.single().complete(ExtractionResponse(emptyList())); runCurrent()
        }
    }
    @Test fun cancelledRequestErrorPersistsAfterDismissingBannerAndRetryClearsOnlyItsError() = runTest {
        withVm { vm, _, api ->
            vm.start("번역", true, true, Language.KO, Language.EN)
            val id = vm.state.value.activeId!!
            vm.addUtterance(id, "나", Language.KO, "안녕하세요"); runCurrent()
            val u = vm.state.value.selected!!.utterances.single()
            val key = "translate:${u.id}"
            vm.cancelRequest(key); vm.dismissMessage()
            assertNotNull(vm.state.value.requestErrors[key])
            vm.translate(id, u.id); runCurrent()
            assertNull(vm.state.value.requestErrors[key])
            api.translations[0].complete(TranslationResponse("old", 1))
            api.translations[1].complete(TranslationResponse("hello", 1)); runCurrent()
            assertEquals("hello", vm.state.value.selected!!.utterances.single().translation)
        }
    }

    @Test fun corruptReadNeverOverwritesOriginalAndCanRetry() = runTest {
        withVm(Store("CORRUPT ORIGINAL")) { vm, store, _ ->
            assertTrue(vm.state.value.loadFailed)
            vm.startRecorded(); vm.retrySave(); runCurrent()
            assertEquals(0, store.writes); assertEquals("CORRUPT ORIGINAL", store.data)
            store.data = null; vm.reload(); runCurrent()
            assertTrue(vm.state.value.loaded)
        }
    }
    @Test fun failedSaveKeepsMemoryAndRetryPersistsLatestDeletion() = runTest {
        withVm { vm, store, _ ->
            store.failWrite = true
            vm.startRecorded(); val id = vm.state.value.activeId!!
            vm.addUtterance(id, "나", Language.KO, "내용"); runCurrent()
            assertTrue(vm.state.value.unsaved); assertEquals(1, vm.state.value.selected!!.utterances.size)
            store.failWrite = false; vm.retrySave(); runCurrent()
            assertFalse(vm.state.value.unsaved); assertEquals(1, ArchiveCodec.decode(store.data!!).size)
            vm.delete(id); runCurrent()
            assertEquals(emptyList(), ArchiveCodec.decode(store.data!!))
        }
    }
    @Test fun allNewSessionsAreRecordedEvenWhenLegacyCallerPassesFalse() = runTest {
        withVm { vm, store, api ->
            vm.start("기록", true, false, Language.KO, Language.EN)
            val id = vm.state.value.activeId!!
            vm.addUtterance(id, "나", Language.KO, "저장되는 문장"); runCurrent()
            vm.end(id)
            api.translations.single().complete(TranslationResponse("late result", 1)); runCurrent()
            assertTrue(vm.state.value.conversations.single().record)
            assertTrue(ArchiveCodec.decode(store.data!!).single().ended)
            assertEquals("저장되는 문장", ArchiveCodec.decode(store.data!!).single().utterances.single().text)
        }
    }
    @Test fun lateTranslationsAfterEditOrEndAreDiscardedButClosedRecordCanTranslate() = runTest {
        withVm { vm, _, api ->
            vm.start("통역", true, true, Language.KO, Language.EN)
            val id = vm.state.value.activeId!!
            vm.addUtterance(id, "나", Language.KO, "원문"); runCurrent()
            val u = vm.state.value.selected!!.utterances.single()
            vm.editUtterance(id, u.id, "수정본")
            api.translations[0].complete(TranslationResponse("stale", 1)); runCurrent()
            assertNull(vm.state.value.selected!!.utterances.single().translation)
            vm.translate(id, u.id); runCurrent(); vm.end(id)
            api.translations[1].complete(TranslationResponse("before end", 2)); runCurrent()
            assertNull(vm.state.value.selected!!.utterances.single().translation)
            vm.translate(id, u.id); runCurrent()
            api.translations[2].complete(TranslationResponse("after end", 2)); runCurrent()
            assertEquals("after end", vm.state.value.selected!!.utterances.single().translation)
        }
    }
    @Test fun newestRequestWinsWhenResponsesArriveInReverseOrder() = runTest {
        withVm { vm, _, api ->
            vm.start("번역", true, true, Language.KO, Language.EN)
            val id = vm.state.value.activeId!!
            vm.addUtterance(id, "나", Language.KO, "원문"); runCurrent()
            val u = vm.state.value.selected!!.utterances.single()
            vm.translate(id, u.id); runCurrent()
            api.translations[1].complete(TranslationResponse("new", 1)); runCurrent()
            api.translations[0].complete(TranslationResponse("old", 1)); runCurrent()
            assertEquals("new", vm.state.value.selected!!.utterances.single().translation)
        }
    }
    @Test fun extractionSnapshotRejectsChangedInputAndRepeatedDraftsPreserveUserWork() = runTest {
        withVm { vm, _, api ->
            vm.startRecorded(); val id = vm.state.value.activeId!!
            vm.addUtterance(id, "나", Language.KO, "보고서 보내 주세요")
            val u = vm.state.value.selected!!.utterances.single()
            val draft = ExtractedItem(u.id, 1, "request", "보고서 보내기", null, null, "보고서 보내 주세요")
            vm.extract(id); runCurrent()
            vm.addUtterance(id, "나", Language.KO, "내일까지요")
            api.extractions[0].complete(ExtractionResponse(listOf(draft))); runCurrent()
            assertTrue(vm.state.value.selected!!.items.isEmpty())
            vm.extract(id); runCurrent(); api.extractions[1].complete(ExtractionResponse(listOf(draft))); runCurrent()
            val item = vm.state.value.selected!!.items.single()
            vm.review(id, item.id); vm.task(id, item.id, TaskStatus.TODO)
            vm.extract(id); runCurrent(); api.extractions[2].complete(ExtractionResponse(listOf(draft))); runCurrent()
            assertEquals(1, vm.state.value.selected!!.items.size)
            assertEquals(TaskStatus.TODO, vm.state.value.selected!!.items.single().status)
        }
    }
    @Test fun deletedSessionCannotReappearFromLateResponse() = runTest {
        withVm { vm, store, api ->
            vm.start("번역", true, true, Language.KO, Language.EN)
            val id = vm.state.value.activeId!!
            vm.addUtterance(id, "나", Language.KO, "원문"); runCurrent()
            vm.delete(id)
            api.translations[0].complete(TranslationResponse("late", 1)); runCurrent()
            assertTrue(vm.state.value.conversations.isEmpty())
            assertTrue(ArchiveCodec.decode(store.data!!).isEmpty())
        }
    }
    @Test fun explicitCancellationDropsLateResultAndClearsBusyState() = runTest {
        withVm { vm, _, api ->
            vm.start("번역", true, true, Language.KO, Language.EN)
            val id = vm.state.value.activeId!!
            vm.addUtterance(id, "나", Language.KO, "원문"); runCurrent()
            val u = vm.state.value.selected!!.utterances.single()
            vm.cancelRequest("translate:${u.id}")
            assertTrue(vm.state.value.busy.isEmpty())
            api.translations.single().complete(TranslationResponse("late", 1)); runCurrent()
            assertNull(vm.state.value.selected!!.utterances.single().translation)
            assertEquals("원문", vm.state.value.selected!!.utterances.single().text)
        }
    }
    @Test fun inventedEvidenceCannotBecomeStoredAction() = runTest {
        withVm { vm, store, api ->
            vm.startRecorded(); val id = vm.state.value.activeId!!
            vm.addUtterance(id, "나", Language.KO, "안녕하세요")
            val u = vm.state.value.selected!!.utterances.single()
            vm.extract(id); runCurrent()
            api.extractions.single().complete(ExtractionResponse(listOf(
                ExtractedItem(u.id, 1, "request", "송금", null, null, "송금해 주세요")
            ))); runCurrent()
            assertTrue(vm.state.value.selected!!.items.isEmpty())
            assertTrue(ArchiveCodec.decode(store.data!!).single().items.isEmpty())
        }
    }
    @Test fun laterCancellationMakesOldTaskReviewableAndEmptyExtractionMarksItOmitted() = runTest {
        withVm { vm, _, api ->
            vm.startRecorded(); val id = vm.state.value.activeId!!
            vm.addUtterance(id, "나", Language.KO, "보고서를 보내 주세요")
            val u = vm.state.value.selected!!.utterances.single()
            val draft = ExtractedItem(u.id, 1, "request", "보고서 보내기", null, null, u.text)
            vm.extract(id); runCurrent(); api.extractions[0].complete(ExtractionResponse(listOf(draft))); runCurrent()
            val item = vm.state.value.selected!!.items.single()
            vm.review(id, item.id); vm.task(id, item.id, TaskStatus.TODO)
            vm.addUtterance(id, "나", Language.KO, "그 요청은 취소합니다. 보내지 마세요.")
            var c = vm.state.value.selected!!
            assertTrue(c.needsReview(c.items.single()))
            vm.task(id, item.id, TaskStatus.DONE)
            assertEquals(TaskStatus.TODO, vm.state.value.selected!!.items.single().status)
            vm.extract(id); runCurrent(); api.extractions[1].complete(ExtractionResponse(emptyList())); runCurrent()
            c = vm.state.value.selected!!
            assertTrue(c.items.single().omittedByLatestExtraction)
            assertTrue(c.needsReview(c.items.single()))
            assertEquals(item.id, c.items.single().id) // Preserve user work; no automatic deletion.
        }
    }
    @Test fun changedOwnerProducesNewDraftWithoutOverwritingTheReviewedItem() = runTest {
        withVm { vm, _, api ->
            vm.startRecorded(); val id = vm.state.value.activeId!!
            vm.addUtterance(id, "나", Language.KO, "민수와 지수 중 민수가 보고서를 보냅니다.")
            val u = vm.state.value.selected!!.utterances.single()
            val draft = ExtractedItem(u.id, 1, "decision", "보고서 보내기", "민수", null, u.text)
            vm.extract(id); runCurrent(); api.extractions[0].complete(ExtractionResponse(listOf(draft))); runCurrent()
            val old = vm.state.value.selected!!.items.single()
            vm.review(id, old.id)
            vm.extract(id); runCurrent(); api.extractions[1].complete(ExtractionResponse(listOf(draft.copy(owner = "지수")))); runCurrent()
            val items = vm.state.value.selected!!.items
            assertEquals(2, items.size)
            assertEquals("민수", items.first { it.id == old.id }.owner)
            assertTrue(items.first { it.id == old.id }.omittedByLatestExtraction)
            assertFalse(items.last().reviewed)
        }
    }
    @Test fun endOrganizesAndPersistsTitleClassificationAndFolders() = runTest {
        withVm { vm, store, api ->
            vm.saveFolder("고객 A"); val folder = vm.state.value.folders.single()
            api.organization = CompletableDeferred()
            vm.startRecorded(); val id = vm.state.value.activeId!!
            vm.addUtterance(id, "나", Language.KO, "고객 A 회의입니다.")
            vm.end(id); runCurrent()
            assertTrue(ArchiveCodec.decode(store.data!!).single().ended) // Saved before AI finishes.
            api.organization!!.complete(OrganizationResponse("고객 A 회의", Category.WORK, WorkType.MEETING, folder.id, "고객 A 회의를 진행했다.")); runCurrent()
            val archive = ArchiveCodec.decodeArchive(store.data!!)
            assertEquals("고객 A 회의", archive.conversations.single().title)
            assertEquals(folder.id, archive.conversations.single().folderId)
            assertEquals("고객 A 회의를 진행했다.", archive.conversations.single().summary)
            assertTrue(archive.conversations.single().organizationAttempted)
            assertEquals(listOf(folder), archive.folders)
            vm.deleteFolder(folder.id); runCurrent()
            assertNull(ArchiveCodec.decode(store.data!!).single().folderId)
            assertEquals(Category.WORK, vm.state.value.selected!!.category)
            assertEquals(1, vm.state.value.selected!!.utterances.size)
        }
    }
    @Test fun lateOrganizationCannotOverwriteManualMetadataOrRepeatOnEnd() = runTest {
        withVm { vm, _, api ->
            api.organization = CompletableDeferred()
            vm.startRecorded(); val id = vm.state.value.activeId!!
            vm.addUtterance(id, "나", Language.KO, "주말 여행 이야기")
            vm.end(id); runCurrent()
            vm.classify(id, "내 여행", Category.DAILY, null, null)
            api.organization!!.complete(OrganizationResponse("잘못된 제목", Category.WORK, WorkType.TASK, null, "잘못된 요약")); runCurrent()
            assertEquals("내 여행", vm.state.value.selected!!.title)
            vm.end(id); runCurrent()
            assertEquals(1, api.organizationCalls)
            assertNull(vm.state.value.selected!!.summary)

        }
    }
    @Test fun folderNamesAreUniqueAndSharedFilterIncludesLinkedTasks() = runTest {
        withVm { vm, store, _ ->
            assertTrue(vm.saveFolder("Client")); assertFalse(vm.saveFolder("client"))
            val id = vm.state.value.folders.single().id
            assertTrue(vm.saveFolder("New client", id)); runCurrent()
            assertEquals("New client", ArchiveCodec.decodeArchive(store.data!!).folders.single().name)
            vm.filterCategory(CategoryFilter(Category.WORK, WorkType.TASK, id))
            vm.deleteFolder(id)
            assertNull(vm.state.value.categoryFilter.folderId)
        }
    }


    @Test fun languageSelectionSwapsOppositeChoiceAndPreservesIdleText() = runTest {
        assertEquals(Language.EN to Language.KO, selectLanguagePair(Language.KO, Language.EN, true, Language.EN))
        assertEquals(Language.EN to Language.KO, selectLanguagePair(Language.KO, Language.EN, false, Language.KO))
        assertEquals(Language.JA to Language.EN, selectLanguagePair(Language.KO, Language.EN, true, Language.JA))
        assertEquals(Language.KO to Language.JA, selectLanguagePair(Language.KO, Language.EN, false, Language.JA))
        withVm { vm, _, _ ->
            vm.updateIdleDraft(UtteranceDraft(text = "작성 중"))
            vm.updateStartDraft(StartDraft(first = Language.EN, second = Language.KO))
            assertEquals(UtteranceDraft(language = Language.EN, text = "작성 중"), vm.state.value.idleDraft)
            vm.updateIdleDraft(UtteranceDraft(language = Language.KO, text = "보존"))
            assertEquals(Language.EN, vm.state.value.idleDraft.language)
            vm.updateStartDraft(StartDraft(first = Language.EN, second = Language.EN))
            assertEquals(Language.KO, vm.state.value.startDraft.second)
        }
    }
    @Test fun swapPreservesTranslationAndDraftWhileNewTargetRejectsLateResponse() = runTest {
        withVm { vm, store, api ->
            vm.start("번역", true, true, Language.KO, Language.EN)
            val id = vm.state.value.activeId!!
            vm.addUtterance(id, Language.KO, "안녕"); runCurrent()
            vm.updateInputDraft(id, UtteranceDraft(text = "작성 중"))
            vm.setLanguages(id, Language.EN, Language.KO)
            assertEquals(Language.EN, vm.state.value.inputDrafts[id]!!.language)
            assertEquals("작성 중", vm.state.value.inputDrafts[id]!!.text)
            api.translations[0].complete(TranslationResponse("hello", 1)); runCurrent()
            assertEquals("hello", vm.state.value.selected!!.utterances.single().translation)
            assertEquals("", vm.state.value.selected!!.utterances.single().speaker)
            vm.setLanguages(id, Language.KO, Language.EN)
            assertEquals("hello", vm.state.value.selected!!.utterances.single().translation)
            val u = vm.state.value.selected!!.utterances.single()
            vm.translate(id, u.id); runCurrent()
            vm.setLanguages(id, Language.KO, Language.JA); runCurrent()
            assertEquals("ja", api.translationInputs.last().to)
            api.translations[1].complete(TranslationResponse("stale English", 1)); runCurrent()
            assertNull(vm.state.value.selected!!.utterances.single().translation)
            api.translations[2].complete(TranslationResponse("こんにちは", 1)); runCurrent()
            assertEquals("こんにちは", ArchiveCodec.decode(store.data!!).single().utterances.single().translation)
        }
    }
    @Test fun failedInitialOrganizationNeverRetriesAndManualSummaryPersists() = runTest {
        withVm { vm, store, api ->
            vm.startRecorded(); val id = vm.state.value.activeId!!
            vm.addUtterance(id, Language.KO, "일정 확인")
            vm.end(id); runCurrent(); vm.end(id); runCurrent()
            assertEquals(1, api.organizationCalls)
            vm.editSummary(id, "일정을 확인했다."); runCurrent()
            val saved = ArchiveCodec.decode(store.data!!).single()
            assertTrue(saved.organizationAttempted)
            assertEquals("일정을 확인했다.", saved.summary)
        }
    }
    @Test fun manualSummaryWinsOverLateInitialAiResult() = runTest {
        withVm { vm, _, api ->
            api.organization = CompletableDeferred()
            vm.startRecorded(); val id = vm.state.value.activeId!!
            vm.addUtterance(id, Language.KO, "주말 산책")
            vm.end(id); runCurrent()
            vm.editSummary(id, "직접 쓴 요약")
            api.organization!!.complete(OrganizationResponse("산책", Category.DAILY, null, null, "AI 요약")); runCurrent()
            assertEquals("직접 쓴 요약", vm.state.value.selected!!.summary)
        }
    }

    @Test fun appearancePersistsWithoutLosingExistingRecordsAndLegacyDefaultsToSystem() = runTest {
        val store = Store()
        withVm(store) { vm, _, _ ->
            vm.startRecorded(); val id = vm.state.value.activeId!!
            vm.addUtterance(id, Language.KO, "보존할 기록")
            vm.setAppearance(Appearance.DARK); runCurrent()
            assertEquals(Appearance.DARK, ArchiveCodec.decodeArchive(store.data!!).appearance)
            assertEquals("보존할 기록", ArchiveCodec.decode(store.data!!).single().utterances.single().text)
        }
        withVm(store) { vm, _, _ -> assertEquals(Appearance.DARK, vm.state.value.appearance) }
        assertEquals(Appearance.SYSTEM, ArchiveCodec.decodeArchive("""{"version":1,"conversations":[]}""").appearance)
    }

    @Test fun sourceCorrectionRejectsPendingInitialSummary() = runTest {
        withVm { vm, _, api ->
            api.organization = CompletableDeferred()
            vm.startRecorded(); val id = vm.state.value.activeId!!
            vm.addUtterance(id, Language.KO, "금요일 배포 확정")
            vm.end(id); runCurrent()
            vm.editUtterance(id, vm.state.value.selected!!.utterances.single().id, "배포 취소")
            api.organization!!.complete(OrganizationResponse("배포 확정", Category.WORK, WorkType.MEETING, null, "금요일 배포 확정")); runCurrent()
            assertNull(vm.state.value.selected!!.summary)
            assertEquals("회의", vm.state.value.selected!!.title)
        }
    }

}
