package kc.ac.uc.speechbridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

enum class Page(val label: String) { TALK("대화"), HISTORY("기록"), TASKS("할 일"), SETTINGS("설정") }
data class BridgeState(
    val page: Page = Page.TALK,
    val appearance: Appearance = Appearance.SYSTEM,
    val conversations: List<Conversation> = emptyList(),
    val selectedId: String? = null,
    val activeId: String? = null,
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    val unsaved: Boolean = false,
    val message: String? = null,
    val connection: Connection = Connection(),
    val health: Health? = null,
    val busy: Set<String> = emptySet(),
    val requestErrors: Map<String, String> = emptyMap(),
    val startDraft: StartDraft = StartDraft(),
    val idleDraft: UtteranceDraft = UtteranceDraft(),
    val inputDrafts: Map<String, UtteranceDraft> = emptyMap(),
    val connectionDraft: Connection = Connection(),
    val historyQuery: String = "",
    val taskQuery: String = "",
    val taskFilter: TaskFilter = TaskFilter.ALL,
    val folders: List<WorkFolder> = emptyList(),
    val categoryFilter: CategoryFilter = CategoryFilter(),
) {
    val selected get() = conversations.firstOrNull { it.id == selectedId }
}

class BridgeViewModel(private val store: SessionStore, private val api: BridgeApi = HttpBridgeApi(), val account: AccountManager? = null) : ViewModel() {
    private val mutable = MutableStateFlow(BridgeState())
    val state = mutable.asStateFlow()
    private val writes = Channel<Pair<Long, String>>(Channel.CONFLATED)
    private var writeRevision = 0L
    private var requestSequence = 0L
    private val requests = mutableMapOf<String, Pair<Long, Job>>()

    init {
        viewModelScope.launch {
            for ((revision, text) in writes) {
                try {
                    store.write(text)
                    if (revision == writeRevision) mutable.update { it.copy(unsaved = false) }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    mutable.update { it.copy(unsaved = true, message = "저장하지 못했습니다. 화면의 내용은 유지됩니다. 저장을 재시도해 주세요.") }
                }
            }
        }
        reload()
    }

    fun reload() {
        if (state.value.loaded) return
        viewModelScope.launch {
            mutable.update { it.copy(loadFailed = false) }
            try {
                val text = store.read()
                val archive = text?.let(ArchiveCodec::decodeArchive) ?: Archive(1, emptyList())
                mutable.update { it.copy(loaded = true, conversations = archive.conversations, folders = archive.folders, appearance = archive.appearance, message = null) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.update { it.copy(loadFailed = true,
                    message = "기록을 읽지 못했습니다. 기존 파일 보호를 위해 변경을 잠갔습니다. 다시 읽기를 시도해 주세요.") }
            }
        }
    }

    fun retrySave() = persist()
    private fun persist() {
        if (!state.value.loaded) return
        writeRevision++
        mutable.update { it.copy(unsaved = true) }
        writes.trySend(writeRevision to ArchiveCodec.encode(state.value.conversations, state.value.folders, state.value.appearance))
    }
    fun setAppearance(appearance: Appearance) {
        if (!state.value.loaded) return
        mutable.update { it.copy(appearance = appearance) }; persist()
    }
    fun navigate(page: Page) { mutable.update { it.copy(page = page) } }
    fun updateStartDraft(draft: StartDraft) {
        if (draft.first == draft.second) return
        mutable.update { it.copy(startDraft = draft, idleDraft = it.idleDraft.copy(language = draft.first)) }
    }
    fun updateIdleDraft(draft: UtteranceDraft) { mutable.update { it.copy(idleDraft = draft.copy(language = it.startDraft.first)) } }
    fun updateInputDraft(id: String, draft: UtteranceDraft) {
        val c = state.value.conversations.find { it.id == id && !it.ended } ?: return
        mutable.update { it.copy(inputDrafts = it.inputDrafts + (id to draft.copy(language = c.first))) }
    }
    fun updateConnectionDraft(draft: Connection) { mutable.update { it.copy(connectionDraft = draft) } }
    fun searchHistory(query: String) { mutable.update { it.copy(historyQuery = query) } }
    fun searchTasks(query: String) { mutable.update { it.copy(taskQuery = query) } }
    fun filterTasks(filter: TaskFilter) { mutable.update { it.copy(taskFilter = filter) } }
    fun dismissMessage() { mutable.update { it.copy(message = null) } }
    fun select(id: String) { mutable.update { it.copy(selectedId = id, page = Page.TALK) } }

    fun start(title: String, translate: Boolean, record: Boolean, first: Language, second: Language) {
        if (!state.value.loaded || state.value.activeId != null) return
        if (translate && first == second) { inform("번역할 두 언어를 다르게 선택해 주세요."); return }
        val c = Conversation(title = title.trim().ifBlank { "새 대화" }, translate = translate, record = true, first = first, second = second)
        mutable.update { it.copy(conversations = it.conversations.filter { c -> c.record } + c,
            activeId = c.id, selectedId = c.id, page = Page.TALK, startDraft = StartDraft(first = first, second = second), idleDraft = UtteranceDraft(language = first)) }
        persist()
    }
    fun end(id: String) {
        val c = state.value.conversations.find { it.id == id && !it.ended } ?: return
        invalidate()
        mutable.update { it.copy(
            conversations = if (c.record) it.conversations.map { c -> if (c.id == id) c.copy(ended = true) else c }
                else it.conversations.filterNot { c -> c.id == id },
            activeId = if (it.activeId == id) null else it.activeId,
            selectedId = if (c.record) id else null,
            inputDrafts = it.inputDrafts - id,
        ) }
        if (c.record) persist()
        if (c.utterances.isNotEmpty()) organize(id)
    }
    fun startConversation() {
        val d = state.value.startDraft
        start("${displayDate(kotlin.time.Clock.System.now().toString())} 대화", true, true, d.first, d.second)
    }
    fun setLanguages(id: String, first: Language, second: Language) {
        if (first == second) return
        val c = state.value.conversations.find { it.id == id && !it.ended } ?: return
        if (c.first == first && c.second == second) return
        val next = c.copy(first = first, second = second)
        val changed = c.utterances.filter { c.target(it.language) != next.target(it.language) }.map { it.id }.toSet()
        val keys = changed.map { "translate:$it" } + "extract:$id"
        keys.forEach { requests.remove(it)?.second?.cancel() }
        mutable.update { state -> state.copy(
            conversations = state.conversations.map { if(it.id == id) next.copy(utterances = next.utterances.map { u ->
                if(u.id in changed) u.copy(translation = null, translationRevision = null) else u
            }) else it },
            inputDrafts = state.inputDrafts + (id to (state.inputDrafts[id] ?: UtteranceDraft()).copy(language = first)),
            busy = state.busy - keys.toSet(), requestErrors = state.requestErrors - keys.toSet(),
        ) }
        persist()
        if(c.translate) changed.forEach { translate(id, it) }
    }
    fun filterCategory(filter: CategoryFilter) { mutable.update { it.copy(categoryFilter = filter) } }
    fun saveFolder(name: String, id: String? = null): Boolean {
        val clean = name.trim()
        if (!state.value.loaded || (clean.isBlank() || clean.length > 200)) return false
        if (state.value.folders.any { it.id != id && it.name.equals(clean, true) }) { inform("같은 이름의 폴더가 있습니다."); return false }
        if (id != null && state.value.folders.none { it.id == id }) return false
        invalidate()
        mutable.update { state -> state.copy(folders = if (id == null) state.folders + WorkFolder(name = clean)
            else state.folders.map { if (it.id == id) it.copy(name = clean) else it }) }
        persist(); return true
    }
    fun deleteFolder(id: String) {
        invalidate()
        mutable.update { state -> state.copy(folders = state.folders.filterNot { it.id == id },
            conversations = state.conversations.map { if (it.folderId == id) it.copy(folderId = null, metadataRevision = it.metadataRevision + 1) else it },
            categoryFilter = if (state.categoryFilter.folderId == id) state.categoryFilter.copy(folderId = null) else state.categoryFilter) }
        persist()
    }
    fun classify(id: String, title: String, category: Category, type: WorkType?, folderId: String?) {
        if (title.isBlank() || (folderId != null && state.value.folders.none { it.id == folderId })) return
        change(id) { it.copy(title = title.trim(), category = category,
            workType = if (category == Category.WORK) type ?: WorkType.OTHER else null,
            folderId = folderId.takeIf { category == Category.WORK }, organized = true,
            organizationSources = it.contextRevisions(), metadataRevision = it.metadataRevision + 1) }
    }
    fun editSummary(id: String, summary: String) {
        if (summary.isBlank() || summary.length > 2000) return
        change(id) { it.copy(summary = summary.trim(), metadataRevision = it.metadataRevision + 1) }
    }
    private fun organize(id: String) {
        val c = state.value.conversations.find { it.id == id } ?: return
        if (!c.ended || c.utterances.isEmpty() || c.organizationAttempted || c.organized) return
        change(id) { it.copy(organizationAttempted = true) }
        val sources = c.utterances.map { SourceText(it.id, it.revision, it.text) }
        val folders = state.value.folders
        launchRequest("organize:$id") {
            val result = api.organize(state.value.connection, OrganizationRequest(sources, c.first.code, folders))
            require(result.title.isNotBlank() && result.title.length <= 80 && result.summary.isNotBlank() && result.summary.length <= 2000)
            require(if (result.category == Category.DAILY) result.workType == null && result.folderId == null
                else result.workType != null && (result.folderId == null || folders.any { it.id == result.folderId }))
            val apply: () -> Unit = { change(id) { current ->
                if (current.metadataRevision != c.metadataRevision || current.contextRevisions() != c.contextRevisions() || state.value.folders != folders) current
                else current.copy(title = result.title.trim(), summary = result.summary.trim(), category = result.category, workType = result.workType,
                    folderId = result.folderId, organized = true, organizationSources = current.contextRevisions(),
                    metadataRevision = current.metadataRevision + 1)
            } }
            apply
        }
    }
    fun delete(id: String) {
        invalidate()
        mutable.update { it.copy(conversations = it.conversations.filterNot { c -> c.id == id },
            selectedId = it.selectedId.takeUnless { selected -> selected == id },
            activeId = it.activeId.takeUnless { active -> active == id }, inputDrafts = it.inputDrafts - id) }
        persist()
    }
    private fun change(id: String, transform: (Conversation) -> Conversation) {
        if (!state.value.loaded) return
        val c = state.value.conversations.firstOrNull { it.id == id } ?: return
        val next = transform(c)
        mutable.update { it.copy(conversations = it.conversations.map { old -> if (old.id == id) next else old }) }
        if (next.record && next != c) persist()
    }
    fun addUtterance(id: String, language: Language, text: String) = addUtterance(id, "", language, text)

    fun addUtterance(id: String, speaker: String, language: Language, text: String) {
        val c = state.value.conversations.find { it.id == id } ?: return
        if (c.ended || text.isBlank()) return
        val u = Utterance(speaker = speaker.trim(), language = language, originalText = text.trim())
        change(id) { it.copy(utterances = it.utterances + u,
            items = it.items.map { item -> if (item.origin == Origin.SERVICE) item.copy(reviewed = false) else item }) }
        mutable.update { it.copy(inputDrafts = it.inputDrafts - id) }
        if (c.translate) translate(id, u.id)
    }
    fun editUtterance(id: String, utteranceId: String, text: String) {
        if (text.isBlank()) return
        invalidate()
        change(id) { it.editUtterance(utteranceId, text) }
    }
    fun addItem(id: String, sourceId: String, kind: ItemKind, text: String, owner: String, due: String) {
        if (text.isBlank()) return
        change(id) { c ->
            val source = c.utterances.find { it.id == sourceId } ?: return@change c
            c.copy(items = c.items + ActionItem(sourceId = sourceId, sourceRevision = source.revision,
                kind = kind, text = text.trim(), owner = owner.trim().ifBlank { null }, dueText = due.trim().ifBlank { null }))
        }
    }
    fun editItem(id: String, item: ActionItem, text: String, owner: String, due: String) {
        if (text.isBlank()) return
        change(id) { it.editItem(item.id, text, owner, due) }
    }
    fun review(id: String, itemId: String) = change(id) { it.reviewItem(itemId) }
    fun task(id: String, itemId: String, status: TaskStatus) {
        try { change(id) { it.setTaskStatus(itemId, status) } }
        catch (e: IllegalArgumentException) { inform(e.message ?: "검토가 필요합니다.") }
    }
    fun deleteItem(id: String, itemId: String) = change(id) { it.copy(items = it.items.filterNot { item -> item.id == itemId }) }

    fun configure(url: String, token: String) {
        try {
            val normalized = validatedBaseUrl(url)
            invalidate()
            val connection = Connection(normalized, token.trim())
            mutable.update { it.copy(connection = connection, connectionDraft = connection, health = null, requestErrors = emptyMap()) }
            inform("연결 설정을 이번 앱 실행 동안만 적용했습니다.")
        } catch (_: Exception) { inform("올바른 HTTPS 주소 또는 로컬 개발 주소를 입력해 주세요.") }
    }
    fun checkHealth() = launchRequest("health") {
        val result = api.health(state.value.connection)
        val apply: () -> Unit = { mutable.update { it.copy(health = result, message = "서버에 도달했습니다. 설정 표시는 실제 API 호출 성공을 뜻하지 않습니다.") } }
        apply
    }
    fun translate(id: String, utteranceId: String) {
        val c = state.value.conversations.find { it.id == id } ?: return
        val u = c.utterances.find { it.id == utteranceId } ?: return
        if (!c.translate) return
        launchRequest("translate:$utteranceId") {
            val result = api.translate(state.value.connection, TranslationRequest(u.text, u.language.code, c.target(u.language).code, u.revision))
            require(result.revision == u.revision && result.text.isNotBlank())
            val apply: () -> Unit = { change(id) { it.translateResult(u.id, u.revision, result.text) } }
            apply
        }
    }
    fun extract(id: String) {
        val c = state.value.conversations.find { it.id == id } ?: return
        if (c.utterances.isEmpty() || !c.record) return
        val sources = c.utterances.map { SourceText(it.id, it.revision, it.text) }
        launchRequest("extract:$id") {
            val result = api.extract(state.value.connection, ExtractionRequest(sources, c.first.code))
            require(result.items.all { item -> sources.any { it.id == item.sourceUtteranceId && it.revision == item.sourceRevision &&
                !item.evidenceQuote.isNullOrBlank() && it.text.contains(item.evidenceQuote) } &&
                item.kind in setOf("request", "decision", "note") && item.text.isNotBlank() })
            val apply: () -> Unit = {
                var applied = false
                change(id) { current ->
                    if (current.utterances.map { SourceText(it.id, it.revision, it.text) } != sources) current
                    else {
                        applied = true
                        val newItems = result.items.map { item -> ActionItem(sourceId = item.sourceUtteranceId,
                            sourceRevision = item.sourceRevision, kind = ItemKind.valueOf(item.kind.uppercase()),
                            text = item.text, owner = item.owner, dueText = item.dueText, origin = Origin.SERVICE,
                            evidenceQuote = item.evidenceQuote, contextRevisions = current.contextRevisions()) }
                        fun sameDraft(a: ActionItem, b: ActionItem) = a.sourceId == b.sourceId &&
                            a.sourceRevision == b.sourceRevision && a.kind == b.kind && a.text == b.text &&
                            a.owner == b.owner && a.dueText == b.dueText
                        // Preserve user work, but explicitly flag AI items absent from the new result.
                        val retained = current.items.map { old ->
                            if (old.origin != Origin.SERVICE) old
                            else if (newItems.none { sameDraft(old, it) }) old.copy(reviewed = false, omittedByLatestExtraction = true)
                            else old.copy(omittedByLatestExtraction = false)
                        }
                        val added = newItems.fold(emptyList<ActionItem>()) { accumulated, item ->
                            if ((retained + accumulated).any { sameDraft(it, item) }) accumulated else accumulated + item
                        }
                        current.copy(items = retained + added)
                    }
                }
                if (applied) inform(if (result.items.isEmpty()) "새 정리 항목이 없습니다. 기존 AI 항목은 취소·변경 여부를 확인해 주세요." else "정리 초안을 만들었습니다. 기존 항목의 변경 여부와 근거를 확인해 주세요.")
                else inform("요청 중 대화가 바뀌어 정리 결과를 적용하지 않았습니다. 다시 요청해 주세요.")
            }
            apply
        }
    }
    private fun inform(text: String) { mutable.update { it.copy(message = text) } }
    fun cancelRequest(key: String) {
        requests.remove(key)?.second?.cancel()
        mutable.update { it.copy(busy = it.busy - key, requestErrors = it.requestErrors + (key to "요청을 취소했습니다. 다시 요청할 수 있습니다."), message = "요청을 취소했습니다. 원문은 유지됩니다.") }
    }
    fun accountChanging() { invalidate() }
    private fun invalidate() {
        requests.values.forEach { it.second.cancel() }
        requests.clear()
        mutable.update { it.copy(busy = emptySet(), requestErrors = emptyMap()) }
    }
    private fun launchRequest(key: String, operation: suspend () -> (() -> Unit)) {
        requests.remove(key)?.second?.cancel()
        val sequence = ++requestSequence
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val apply = operation()
                if (requests[key]?.first == sequence) apply()
            } catch (e: CancellationException) { throw e }
            catch (e: BridgeRequestException) {
                if (requests[key]?.first == sequence) requestFailed(key, "${e.message} 원문은 유지됩니다.")
            }
            catch (_: Exception) {
                if (requests[key]?.first == sequence) requestFailed(key, "요청하지 못했습니다. 연결 상태와 서버 준비를 확인한 뒤 다시 시도해 주세요. 원문은 유지됩니다.")
            } finally {
                if (requests[key]?.first == sequence) {
                    requests.remove(key)
                    mutable.update { it.copy(busy = it.busy - key) }
                }
            }
        }
        requests[key] = sequence to job
        mutable.update { it.copy(busy = it.busy + key, requestErrors = it.requestErrors - key) }
        job.start()
    }
    private fun requestFailed(key: String, message: String) {
        mutable.update { it.copy(requestErrors = it.requestErrors + (key to message), message = message) }
    }
    override fun onCleared() { invalidate(); writes.close(); api.close(); account?.close() }
}
