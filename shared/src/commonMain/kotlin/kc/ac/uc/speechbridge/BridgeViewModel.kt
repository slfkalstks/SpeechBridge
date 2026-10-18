package kc.ac.uc.speechbridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

enum class Page(val label: String) { TALK("대화"), HISTORY("기록"), TASKS("할 일"), SETTINGS("설정") }
data class BridgeState(
    val page: Page = Page.TALK,
    val appearance: Appearance = Appearance.SYSTEM,
    val conversations: List<Conversation> = emptyList(),
    val selectedId: String? = null,
    val activeId: String? = null,
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    val accountRequired: Boolean = false,
    val cloudMode: Boolean = false,
    val syncPending: Int = 0,
    val syncBusy: Boolean = false,
    val syncProblem: String? = null,
    val syncConflict: Boolean = false,
    val workspaceBusy: Boolean = false,
    val legacyCount: Int = 0,
    val legacyFolders: Int = 0,
    val unsaved: Boolean = false,
    val message: String? = null,
    val connection: Connection = Connection(),
    val health: Health? = null,
    val busy: Set<String> = emptySet(),
    val requestErrors: Map<String, String> = emptyMap(),
    val organizationJobs: Map<String,String> = emptyMap(),
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

class BridgeViewModel(private val store: SessionStore, private val api: BridgeApi = HttpBridgeApi(), val account: AccountManager? = null, private val sync: SyncRepository? = null, audioDevice: AudioDevice = UnavailableAudioDevice(), voiceApi: VoiceApi = UnavailableVoiceApi()) : ViewModel() {
    private val mutable = MutableStateFlow(BridgeState(cloudMode = sync != null, accountRequired = sync != null))
    val state = mutable.asStateFlow()
    private data class Write(val revision: Long, val generation: Long, val identity: SyncIdentity?,
        val archive: Archive, val drafts: Map<String, JsonObject>)
    private sealed interface WriteRequest {
        data class Save(val value: Write) : WriteRequest
        data class Barrier(val done: CompletableDeferred<Unit>) : WriteRequest
    }
    private val writes = Channel<WriteRequest>(Channel.UNLIMITED)
    private var generation = 0L
    private var identity: SyncIdentity? = null
    private var upload: Job? = null
    private var cloudLoad: Job? = null
    private var workspaceOperation: Job? = null
    private var writeRevision = 0L
    private var requestSequence = 0L
    private val requests = mutableMapOf<String, Pair<Long, Job>>()
    val voice = VoiceController(viewModelScope, audioDevice, voiceApi, ::recordTranscript)
    fun startVoice() {
        if (!canEdit() || state.value.page != Page.TALK || state.value.selected?.ended == true) return
        voice.start()
    }
    fun cancelVoice() = voice.cancel()
    fun speak(id: String, utteranceId: String) {
        if (!canEdit()) return
        val c = state.value.conversations.find { it.id == id } ?: return
        val u = c.utterances.find { it.id == utteranceId && it.translationRevision == it.revision } ?: return
        val text = u.translation ?: return
        if (voice.state.value.playingKey == u.id) voice.cancel() else voice.speak(text, c.target(u.language), u.id)
    }
    private fun recordTranscript(result: Transcription) {
        if (!canEdit() || state.value.page != Page.TALK || state.value.selected?.ended == true) return
        val detected = Language.entries.firstOrNull { it.code == result.language } ?: return
        val oldDraft = state.value.activeId?.let { state.value.inputDrafts[it] } ?: state.value.idleDraft
        if (state.value.activeId == null) {
            val pair = state.value.startDraft
            val target = if (detected == pair.second) pair.first else pair.second
            updateStartDraft(pair.copy(first = detected, second = target))
            startConversation()
        }
        val id = state.value.activeId ?: return
        val c = state.value.conversations.first { it.id == id }
        val target = if (detected == c.second) c.first else c.second
        setLanguages(id, detected, target)
        addUtterance(id, detected, result.text)
        // Speech must not consume text the user has not submitted.
        if (oldDraft.text.isNotEmpty()) updateInputDraft(id, oldDraft)
    }

    init {
        viewModelScope.launch {
            for (request in writes) {
                if (request is WriteRequest.Barrier) { request.done.complete(Unit); continue }
                val write = (request as WriteRequest.Save).value
                if (write.generation != generation) continue
                try {
                    if (sync == null) store.write(ArchiveCodec.encode(write.archive.conversations, write.archive.folders, write.archive.appearance))
                    else {
                        // Preserve each component in the account's retry queue even when an
                        // earlier component could not reach secure storage.
                        var failure: Exception? = null
                        suspend fun attempt(block: suspend () -> Unit) {
                            try { block() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { failure = e }
                        }
                        attempt { sync.save(write.archive, write.identity) }
                        for (id in sync.currentDrafts().keys + write.drafts.keys) attempt { sync.saveDraft(id, write.drafts[id], write.identity) }
                        failure?.let { throw it }
                    }
                    if (write.generation == generation && write.identity == identity) {
                        if (write.revision == writeRevision) mutable.update { it.copy(unsaved = false) }
                        sync?.let { repository ->
                            val count = repository.pendingCount()
                            mutable.update { it.copy(syncPending = count) }
                            retrySync()
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (write.generation == generation && write.identity == identity) mutable.update { it.copy(unsaved = true,
                        message = "저장하지 못했습니다. 화면의 내용은 유지됩니다. 저장을 재시도해 주세요.") }
                }
            }
        }
        if (sync == null) reload()
        else {
            requireNotNull(account)
            viewModelScope.launch {
                account.state.map { it.ready to it.user?.id }.distinctUntilChanged().collect { (ready, userId) ->
                    if (!ready) return@collect
                    cloudLoad?.cancel(); upload?.cancel(); workspaceOperation?.cancel(); invalidate()
                    // Hide the old workspace immediately, then finish its already queued secure writes.
                    // Bumping the generation first would discard input received just before expiry.
                    identity = null
                    mutable.value = BridgeState(cloudMode = true, accountRequired = true, loaded = userId == null)
                    drainWrites()
                    generation++
                    if (account.state.value.user?.id != userId) return@collect
                    identity = userId?.let { SyncIdentity(account.endpoint, it) }
                    mutable.update { it.copy(accountRequired = userId == null) }
                    if (userId == null) sync.closeAccount() else loadCloud()
                }
            }
            viewModelScope.launch {
                while (isActive) {
                    delay(15_000)
                    if (identity == null || state.value.workspaceBusy || state.value.unsaved || state.value.syncConflict) continue
                    if (!state.value.loaded && state.value.loadFailed) loadCloud()
                    else if (state.value.syncPending > 0) retrySync()
                    else if (state.value.organizationJobs.values.any { it == "pending" || it == "running" }) pollOrganization()
                }
            }
        }
    }

    fun reload() {
        if (sync != null) { loadCloud(); return }
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

    private fun restoredWorkspace(current: BridgeState, archive: Archive, drafts: Map<String,JsonObject>, copies: Map<String,String> = emptyMap()): BridgeState {
        fun language(value: JsonObject?, name: String, fallback: Language) =
            Language.entries.firstOrNull { it.name == value?.get(name)?.jsonPrimitive?.content } ?: fallback
        val initial = drafts["new"]
        val first = language(initial,"first",Language.KO)
        val second = language(initial,"second",Language.EN)
        val active = (copies[current.activeId] ?: current.activeId).takeIf { id -> archive.conversations.any { it.id == id && !it.ended } }
            ?: archive.conversations.lastOrNull { !it.ended }?.id
        val selected = (copies[current.selectedId] ?: current.selectedId).takeIf { id -> archive.conversations.any { it.id == id } } ?: active
        return current.copy(loaded = true, conversations = archive.conversations, folders = archive.folders,
            appearance = archive.appearance, activeId = active, selectedId = selected,
            startDraft = StartDraft(first = first, second = second),
            idleDraft = UtteranceDraft(first,initial?.get("text")?.jsonPrimitive?.content.orEmpty()),
            inputDrafts = drafts.filterKeys { id -> archive.conversations.any { it.id == id && !it.ended } }
                .mapValues { (id,value) -> UtteranceDraft(language(value,"language",archive.conversations.first { it.id == id }.first),value["text"]?.jsonPrimitive?.content.orEmpty()) })
    }
    private fun loadCloud() {
        val repository = sync ?: return
        val user = identity ?: return
        if (cloudLoad?.isActive == true) return
        val epoch = generation
        cloudLoad = viewModelScope.launch {
            mutable.update { it.copy(loadFailed = false) }
            try {
                val archive = repository.open(user)
                val drafts = repository.currentDrafts()
                val jobs = repository.currentOrganizationJobs()
                val pending = repository.pendingCount()
                if (epoch != generation) return@launch
                mutable.update { restoredWorkspace(it, archive, drafts).copy(syncPending = pending, organizationJobs = jobs) }
                inspectLegacy()
                retrySync()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (epoch == generation) mutable.update { it.copy(loadFailed = true,
                    message = "서버 기록을 불러오지 못했습니다. 연결을 확인하고 다시 시도해 주세요. 전송 대기 내용은 보존됩니다.") }
            }
        }
    }
    private suspend fun pollOrganization() {
        val repository=sync ?: return
        val user=identity ?: return
        val epoch=generation
        val revision=writeRevision
        fun eligible() = epoch == generation && identity == user && writeRevision == revision &&
            !state.value.unsaved && !state.value.workspaceBusy && !state.value.syncBusy &&
            state.value.busy.isEmpty() && voice.state.value.phase in setOf(VoicePhase.IDLE,VoicePhase.ERROR)
        try {
            val result=repository.refreshIfIdle(user,::eligible) ?: return
            mutable.update { restoredWorkspace(it,result.archive,result.drafts).copy(organizationJobs=result.organizationJobs) }
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) { /* A background read never discards memory or spams notifications. */ }
    }
    fun retrySync() {
        val repository = sync ?: return
        if (identity == null || !state.value.loaded || state.value.workspaceBusy || upload?.isActive == true) return
        val epoch = generation
        upload = viewModelScope.launch {
            mutable.update { it.copy(syncBusy = true, syncProblem = null) }
            try { repository.flush(); if(epoch == generation) mutable.update { it.copy(syncConflict = false) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (epoch == generation) mutable.update { it.copy(syncConflict = e is BridgeRequestException && e.code in setOf("version_conflict", "mutation_reused"),
                    syncProblem = if (e is BridgeRequestException) e.message else "연결되면 다시 전송할 수 있습니다.") }
            } finally {
                if (epoch == generation) {
                    val count = repository.pendingCount()
                    mutable.update { it.copy(syncBusy = false, syncPending = count) }
                }
            }
        }
    }
    private suspend fun inspectLegacy() {
        try {
            val legacy = store.read()?.let(ArchiveCodec::decodeArchive)
            mutable.update { it.copy(legacyCount = legacy?.conversations?.size ?: 0, legacyFolders = legacy?.folders?.size ?: 0) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { inform("기존 기기 기록을 읽지 못했습니다. 원본은 보관되어 있습니다.") }
    }
    private fun workspaceOperation(operation: suspend (SyncRepository, SyncIdentity) -> SyncWorkspace) {
        val repository = sync ?: return
        val user = identity ?: return
        if (state.value.workspaceBusy || !state.value.loaded) return
        val epoch = generation
        mutable.update { it.copy(workspaceBusy = true) }
        invalidate()
        workspaceOperation = viewModelScope.launch {
            try {
                drainWrites()
                upload?.cancelAndJoin()
                if (state.value.unsaved) throw BridgeRequestException("secure_storage_failed", "먼저 저장을 재시도해 주세요.")
                val result = operation(repository, user)
                val drafts = repository.currentDrafts()
                val jobs = repository.currentOrganizationJobs()
                if (epoch != generation) return@launch
                val pending = repository.pendingCount()
                mutable.update { restoredWorkspace(it,result.archive,drafts,result.copies).copy(
                    syncPending = pending, syncConflict = false, syncProblem = null, organizationJobs = jobs) }
                inspectLegacy()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (epoch == generation) inform(if(e is BridgeRequestException) e.message.orEmpty() else "완료하지 못했습니다. 기존 기록은 보관되어 있습니다. 연결 상태를 확인하고 다시 시도해 주세요.")
            } finally {
                if (epoch == generation) mutable.update { it.copy(workspaceBusy = false) }
            }
            if (epoch == generation) retrySync()
        }
    }
    fun importLegacyRecords() = workspaceOperation { repository, user ->
        val original = store.read()
        val legacy = original?.let(ArchiveCodec::decodeArchive) ?: Archive(1, emptyList())
        val result = repository.importArchive(legacy, user)
        // No file changes before verifying the server copy, and no overwrite of a
        // source that changed during migration.
        if (store.read() == original) store.write(ArchiveCodec.encode(emptyList()))
        SyncWorkspace(result)
    }
    fun resolveSyncConflict() = workspaceOperation { repository, user -> repository.resolveKeepingBoth(user) }
    fun refreshRecords() = workspaceOperation { repository, user -> SyncWorkspace(repository.open(user)) }
    private suspend fun drainWrites() {
        val barrier = CompletableDeferred<Unit>()
        writes.send(WriteRequest.Barrier(barrier))
        barrier.await()
    }
    suspend fun prepareAccountChange(): Boolean {
        if (state.value.workspaceBusy) { inform("기록 처리가 끝난 뒤 다시 시도해 주세요."); return false }
        drainWrites()
        if (state.value.unsaved) { inform("아직 안전하게 저장하지 못한 내용이 있습니다. 저장을 재시도한 뒤 로그아웃해 주세요."); return false }
        upload?.cancelAndJoin()
        invalidate()
        return true
    }
    private fun canEdit() = state.value.loaded && !state.value.accountRequired && !state.value.workspaceBusy
    fun retrySave() = persist()
    private fun persist() {
        if (!canEdit()) return
        val current = state.value
        val drafts = if (sync == null) emptyMap() else buildMap {
            fun draft(text: String, first: Language, second: Language) = buildJsonObject {
                put("text", text); put("language", first.name); put("first", first.name); put("second", second.name)
            }
            if (current.idleDraft.text.isNotEmpty() || current.startDraft.first != Language.KO || current.startDraft.second != Language.EN)
                put("new", draft(current.idleDraft.text, current.startDraft.first, current.startDraft.second))
            for ((id, input) in current.inputDrafts) {
                val conversation = current.conversations.find { it.id == id && !it.ended } ?: continue
                if (input.text.isNotEmpty()) put(id, draft(input.text, conversation.first, conversation.second))
            }
        }
        writeRevision++
        mutable.update { it.copy(unsaved = true) }
        writes.trySend(WriteRequest.Save(Write(writeRevision, generation, identity,
            Archive(1, current.conversations.filter { it.record }, current.folders, current.appearance), drafts)))
    }
    fun setAppearance(appearance: Appearance) {
        if (!canEdit()) return
        mutable.update { it.copy(appearance = appearance) }; persist()
    }
    fun navigate(page: Page) { voice.cancel(); mutable.update { it.copy(page = page) } }
    fun updateStartDraft(draft: StartDraft) {
        if (!canEdit() || draft.first == draft.second) return
        voice.cancel()
        mutable.update { it.copy(startDraft = draft, idleDraft = it.idleDraft.copy(language = draft.first)) }
        if (sync != null) persist()
    }
    fun updateIdleDraft(draft: UtteranceDraft) {
        if (!canEdit()) return
        mutable.update { it.copy(idleDraft = draft.copy(language = it.startDraft.first)) }
        if (sync != null) persist()
    }
    fun updateInputDraft(id: String, draft: UtteranceDraft) {
        if (!canEdit()) return
        val c = state.value.conversations.find { it.id == id && !it.ended } ?: return
        mutable.update { it.copy(inputDrafts = it.inputDrafts + (id to draft.copy(language = c.first))) }
        if (sync != null) persist()
    }
    fun updateConnectionDraft(draft: Connection) { mutable.update { it.copy(connectionDraft = draft) } }
    fun searchHistory(query: String) { mutable.update { it.copy(historyQuery = query) } }
    fun searchTasks(query: String) { mutable.update { it.copy(taskQuery = query) } }
    fun filterTasks(filter: TaskFilter) { mutable.update { it.copy(taskFilter = filter) } }
    fun dismissMessage() { mutable.update { it.copy(message = null) } }
    fun select(id: String) { voice.cancel(); mutable.update { state -> state.copy(selectedId = id, page = Page.TALK,
        activeId = state.conversations.firstOrNull { it.id == id && !it.ended }?.id ?: state.activeId) } }

    fun start(title: String, translate: Boolean, record: Boolean, first: Language, second: Language) {
        if (!canEdit() || state.value.activeId != null) return
        if (translate && first == second) { inform("번역할 두 언어를 다르게 선택해 주세요."); return }
        val c = Conversation(title = title.trim().ifBlank { "새 대화" }, translate = translate, record = true, first = first, second = second)
        mutable.update { it.copy(conversations = it.conversations.filter { c -> c.record } + c,
            activeId = c.id, selectedId = c.id, page = Page.TALK, startDraft = StartDraft(first = first, second = second), idleDraft = UtteranceDraft(language = first)) }
        persist()
    }
    fun end(id: String) {
        if (!canEdit()) return
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
        if (c.utterances.isNotEmpty()) {
            if(sync == null) organize(id)
            else if(!c.organizationAttempted && !c.organized) mutable.update { it.copy(organizationJobs = it.organizationJobs + (id to "pending")) }
        }
    }
    fun startConversation() {
        val d = state.value.startDraft
        start("${displayDate(kotlin.time.Clock.System.now().toString())} 대화", true, true, d.first, d.second)
    }
    fun setLanguages(id: String, first: Language, second: Language) {
        if (!canEdit()) return
        if (first == second) return
        val c = state.value.conversations.find { it.id == id && !it.ended } ?: return
        if (c.first == first && c.second == second) return
        voice.cancel()
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
        if (!canEdit() || (clean.isBlank() || clean.length > 200)) return false
        if (state.value.folders.any { it.id != id && it.name.equals(clean, true) }) { inform("같은 이름의 폴더가 있습니다."); return false }
        if (id != null && state.value.folders.none { it.id == id }) return false
        invalidate()
        mutable.update { state -> state.copy(folders = if (id == null) state.folders + WorkFolder(name = clean)
            else state.folders.map { if (it.id == id) it.copy(name = clean) else it }) }
        persist(); return true
    }
    fun deleteFolder(id: String) {
        if (!canEdit()) return
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
        if (!canEdit()) return
        invalidate()
        mutable.update { it.copy(conversations = it.conversations.filterNot { c -> c.id == id },
            selectedId = it.selectedId.takeUnless { selected -> selected == id },
            activeId = it.activeId.takeUnless { active -> active == id }, inputDrafts = it.inputDrafts - id) }
        persist()
    }
    private fun change(id: String, transform: (Conversation) -> Conversation) {
        if (!canEdit()) return
        val c = state.value.conversations.firstOrNull { it.id == id } ?: return
        val next = transform(c)
        mutable.update { it.copy(conversations = it.conversations.map { old -> if (old.id == id) next else old }) }
        if (next.record && next != c) persist()
    }
    fun addUtterance(id: String, language: Language, text: String) = addUtterance(id, "", language, text)

    fun addUtterance(id: String, speaker: String, language: Language, text: String) {
        if (!canEdit()) return
        val c = state.value.conversations.find { it.id == id } ?: return
        if (c.ended || text.isBlank()) return
        val u = Utterance(speaker = speaker.trim(), language = language, originalText = text.trim())
        change(id) { it.copy(utterances = it.utterances + u,
            items = it.items.map { item -> if (item.origin == Origin.SERVICE) item.copy(reviewed = false) else item }) }
        mutable.update { it.copy(inputDrafts = it.inputDrafts - id) }
        if (sync != null) persist()
        if (c.translate) translate(id, u.id)
    }
    fun editUtterance(id: String, utteranceId: String, text: String) {
        voice.cancel()
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
    private fun invalidate() {
        voice.cancel()
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
    override fun onCleared() { invalidate(); voice.close(); writes.close(); api.close(); account?.close(); sync?.close() }
}
