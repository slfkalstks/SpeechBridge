package kc.ac.uc.speechbridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

data class SyncWorkspace(val archive: Archive, val copies: Map<String, String> = emptyMap())
@Serializable data class SyncIdentity(val origin: String, val userId: String)
@Serializable data class PendingMutation(val deviceId: String, val mutationId: String, val entity: String,
    val id: String, val baseVersion: String, val value: JsonElement?)
@Serializable data class MutationReceipt(val entity: String, val id: String, val version: String, val cursor: String)
@Serializable data class DataSnapshot(val archive: Archive, val versions: Map<String, String>, val cursor: String,
    val drafts: Map<String, JsonObject> = emptyMap(), val organizationJobs: Map<String,String> = emptyMap())
interface SyncRemote {
    suspend fun snapshot(identity: SyncIdentity, deviceId: String): DataSnapshot
    suspend fun mutate(identity: SyncIdentity, mutation: PendingMutation): MutationReceipt
    fun close() {}
}
@Serializable private data class PendingEnvelope(val version: Int = 1, val deviceId: String = newId(),
    val accounts: Map<String, List<PendingMutation>> = emptyMap())

/** Only unsent mutations are persisted; server history and versions live in memory. */
class SyncRepository(private val secure: CredentialStore, private val remote: SyncRemote) {
    private val json = ArchiveCodec.json
    private val mutex = Mutex()
    private val uploadMutex = Mutex()
    private var envelope: PendingEnvelope? = null
    // Failed secure writes remain in this process only, isolated by account. Never upload
    // them until a subsequent secure commit succeeds. The UI keeps its unsaved warning.
    private val volatileQueues = mutableMapOf<String, List<PendingMutation>>()
    private var identity: SyncIdentity? = null
    private var archive = Archive(1, emptyList())
    private var versions = emptyMap<String, String>()
    private var drafts = emptyMap<String, JsonObject>()
    private var organizationJobs = emptyMap<String,String>()
    private fun key(identity: SyncIdentity) = json.encodeToString(identity)
    private fun queue(identity: SyncIdentity) = volatileQueues[key(identity)] ?: envelope!!.accounts[key(identity)].orEmpty()
    private suspend fun persist(next: PendingEnvelope) {
        secure.write(json.encodeToString(next))
        envelope = next // State only advances after durable secure storage succeeds.
    }
    private suspend fun load() {
        if (envelope != null) return
        val saved = secure.read()
        if (saved == null) persist(PendingEnvelope())
        else {
            val next = json.decodeFromString<PendingEnvelope>(saved)
            require(next.version == 1 && next.deviceId.isNotBlank())
            require(next.accounts.values.all { list -> list.map { it.mutationId }.distinct().size == list.size && list.all { it.deviceId == next.deviceId } })
            envelope = next
        }
    }
    private fun overlay(source: Archive, pending: List<PendingMutation>): Archive {
        var result = source
        for (mutation in pending) when(mutation.entity) {
            "conversation" -> result = result.copy(conversations = result.conversations.filterNot { it.id == mutation.id } +
                listOfNotNull(mutation.value?.let { json.decodeFromJsonElement<Conversation>(it) }))
            "folder" -> result = result.copy(folders = result.folders.filterNot { it.id == mutation.id } +
                listOfNotNull(mutation.value?.let { json.decodeFromJsonElement<WorkFolder>(it) }))
            "settings" -> result = result.copy(appearance = Appearance.valueOf(mutation.value!!.jsonObject.getValue("appearance").jsonPrimitive.content))
        }
        return result
    }
    suspend fun open(next: SyncIdentity): Archive = mutex.withLock {
        require(next.origin.isNotBlank() && next.userId.isNotBlank())
        load()
        volatileQueues[key(next)]?.let { enqueue(it, next) }
        // Clear the prior account before any I/O; an error must not reveal its memory.
        identity = null; archive = Archive(1, emptyList()); versions = emptyMap(); drafts = emptyMap(); organizationJobs = emptyMap()
        val pending = queue(next)
        var online = true
        val snapshot = try { remote.snapshot(next, envelope!!.deviceId) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is BridgeRequestException && e.code in setOf("unauthorized", "login_required")) throw e
            if (pending.isEmpty()) throw e
            online = false
            // Offline restart recovers only pending data, not a permanent history cache.
            DataSnapshot(Archive(1, emptyList()), pending.associate { "${it.entity}:${it.id}" to it.baseVersion }, "0")
        }
        require(snapshot.archive.version == 1)
        versions = snapshot.versions
        organizationJobs = snapshot.organizationJobs
        drafts = snapshot.drafts.toMutableMap().apply {
            pending.filter { it.entity == "draft" }.forEach { if(it.value == null) remove(it.id) else put(it.id, it.value.jsonObject) }
        }
        archive = overlay(snapshot.archive, pending)
        val detached = drafts.filter { (id,value) -> id != "new" && value["text"]?.jsonPrimitive?.content.orEmpty().isNotEmpty() &&
            archive.conversations.none { it.id == id && !it.ended } }
        if (!online && detached.isNotEmpty()) throw BridgeRequestException("offline_draft_context",
            "작성 중인 내용은 보관되어 있습니다. 서버에 연결하면 원래 대화와 함께 다시 불러옵니다.")
        if (detached.isNotEmpty()) {
            val recovered = archive.conversations.toMutableList()
            val nextDrafts = drafts.toMutableMap()
            val nextPending = pending.toMutableList()
            fun add(entity:String,id:String,value:JsonElement?) { nextPending += mutation(entity,id,value,nextPending) }
            for ((scope,value) in detached) {
                val record = draftConversation(value)
                recovered += record
                add("conversation",record.id,json.encodeToJsonElement(record))
                add("draft",record.id,value); add("draft",scope,null)
                nextDrafts.remove(scope); nextDrafts[record.id] = value
            }
            enqueue(nextPending,next)
            archive = archive.copy(conversations = recovered); drafts = nextDrafts
        }
        identity = next
        archive
    }
    private fun draftConversation(value:JsonObject):Conversation {
        fun language(key:String,fallback:Language) = Language.entries.firstOrNull { it.name == value[key]?.jsonPrimitive?.content } ?: fallback
        val first = language("first",Language.KO)
        val second = language("second",Language.EN).takeIf { it != first } ?: if(first == Language.KO) Language.EN else Language.KO
        return Conversation(title="복구한 입력",translate=true,record=true,first=first,second=second)
    }
    suspend fun closeAccount() = mutex.withLock {
        identity = null; archive = Archive(1, emptyList()); versions = emptyMap(); drafts = emptyMap(); organizationJobs = emptyMap()
    }
    suspend fun pendingCount(): Int = mutex.withLock { identity?.let { queue(it).size } ?: 0 }
    suspend fun currentDrafts(): Map<String, JsonObject> = mutex.withLock { drafts.toMap() }
    suspend fun currentOrganizationJobs(): Map<String,String> = mutex.withLock { organizationJobs.toMap() }

    /** A background read never holds up secure writes or applies over newer UI input. */
    suspend fun refreshIfIdle(user:SyncIdentity, accept:()->Boolean):DataSnapshot? {
        val baseline = mutex.withLock {
            if(identity != user || queue(user).isNotEmpty() || !accept()) return null
            Triple(archive,versions,drafts) to envelope!!.deviceId
        }
        val snapshot = remote.snapshot(user,baseline.second)
        require(snapshot.archive.version == 1)
        return mutex.withLock {
            if(identity != user || queue(user).isNotEmpty() || !accept() || Triple(archive,versions,drafts) != baseline.first) return@withLock null
            // Detached drafts need open()'s durable recovery, never a silent background replacement.
            if(snapshot.drafts.any { (id,value) -> id != "new" && value["text"]?.jsonPrimitive?.content.orEmpty().isNotEmpty() &&
                    snapshot.archive.conversations.none { it.id == id && !it.ended } }) return@withLock null
            archive=snapshot.archive;versions=snapshot.versions;drafts=snapshot.drafts;organizationJobs=snapshot.organizationJobs
            snapshot
        }
    }

    private fun mutation(entity: String, id: String, value: JsonElement?, pending: List<PendingMutation>): PendingMutation {
        val prior = pending.lastOrNull { it.entity == entity && it.id == id }
        val base = prior?.baseVersion?.toLong()?.plus(1)?.toString() ?: versions["$entity:$id"] ?: "0"
        return PendingMutation(envelope!!.deviceId, newId(), entity, id, base, value)
    }
    private suspend fun enqueue(next: List<PendingMutation>, user: SyncIdentity) {
        val accounts = if (next.isEmpty()) envelope!!.accounts - key(user) else envelope!!.accounts + (key(user) to next)
        try {
            persist(envelope!!.copy(accounts = accounts))
            volatileQueues.remove(key(user))
        } catch (e: Exception) {
            volatileQueues[key(user)] = next
            throw e
        }
    }
    suspend fun save(next: Archive, expectedIdentity: SyncIdentity? = null) = mutex.withLock {
        val user = identity ?: throw BridgeRequestException("login_required", "로그인 후 기록을 저장할 수 있습니다.")
        check(expectedIdentity == null || user == expectedIdentity) { "Account changed" }
        require(next.version == 1)
        val pending = queue(user).toMutableList()
        fun add(entity: String, id: String, value: JsonElement?) { pending += mutation(entity, id, value, pending) }
        // Parent folders before records; record updates before deleting a folder.
        for (folder in next.folders) if (archive.folders.find { it.id == folder.id } != folder) add("folder", folder.id, json.encodeToJsonElement(folder))
        for (conversation in next.conversations) if (archive.conversations.find { it.id == conversation.id } != conversation) add("conversation", conversation.id, json.encodeToJsonElement(conversation))
        for (conversation in archive.conversations) if (next.conversations.none { it.id == conversation.id }) add("conversation", conversation.id, null)
        for (folder in archive.folders) if (next.folders.none { it.id == folder.id }) add("folder", folder.id, null)
        if (archive.appearance != next.appearance) add("settings", "settings", buildJsonObject { put("appearance", next.appearance.name) })
        try {
            if (pending.size != queue(user).size || key(user) in volatileQueues) enqueue(pending, user)
        } finally {
            // Effective in-memory state includes the failed write, so a later undo is
            // represented too. Only enqueue's successful secure commit marks it durable.
            archive = next
        }
    }
    suspend fun saveDraft(id: String, value: JsonObject?, expectedIdentity: SyncIdentity? = null) = mutex.withLock {
        val user = identity ?: throw BridgeRequestException("login_required", "로그인 후 입력해 주세요.")
        check(expectedIdentity == null || user == expectedIdentity) { "Account changed" }
        if (drafts[id] == value) {
            volatileQueues[key(user)]?.let { enqueue(it, user) }
            return@withLock
        }
        val pending = queue(user)
        try { enqueue(pending + mutation("draft", id, value, pending), user) }
        finally { drafts = drafts.toMutableMap().apply { if(value == null) remove(id) else put(id,value) } }
    }
    /** Caller confirms ownership first. Colliding content is never silently replaced. */
    suspend fun importArchive(source: Archive, user: SyncIdentity): Archive {
        val merged = mutex.withLock {
            check(identity == user)
            for (folder in source.folders) {
                val existing = archive.folders.find { it.id == folder.id || it.name.equals(folder.name, true) }
                if (existing != null && existing != folder) throw BridgeRequestException("import_conflict", "같은 이름이나 ID의 폴더가 서버에 있습니다. 기존 기록은 그대로 보관했습니다.")
            }
            for (record in source.conversations) {
                val existing = archive.conversations.find { it.id == record.id }
                if (existing != null && existing != record) throw BridgeRequestException("import_conflict", "같은 기록이 서버에서 변경되었습니다. 덮어쓰지 않고 기기 원본을 보관했습니다.")
            }
            archive.copy(conversations = archive.conversations + source.conversations.filter { c -> archive.conversations.none { it.id == c.id } },
                folders = archive.folders + source.folders.filter { f -> archive.folders.none { it.id == f.id } })
        }
        save(merged, user)
        flush()
        return mutex.withLock {
            check(identity == user)
            val snapshot = remote.snapshot(user, envelope!!.deviceId)
            check(source.conversations.all { record -> snapshot.archive.conversations.any { it == record } } &&
                source.folders.all { folder -> snapshot.archive.folders.any { it == folder } }) { "Import verification failed" }
            check(queue(user).isEmpty())
            versions = snapshot.versions; archive = snapshot.archive; drafts = snapshot.drafts
            archive
        }
    }

    /** Explicit keep-both choice: server originals remain, local non-deleted edits become copies.
     * The replacement queue is committed atomically before the old mutations can be discarded. */
    suspend fun resolveKeepingBoth(user: SyncIdentity): SyncWorkspace = uploadMutex.withLock {
        mutex.withLock {
            check(identity == user)
            val pending = queue(user)
            val snapshot = remote.snapshot(user, envelope!!.deviceId)
            val folders = snapshot.archive.folders.toMutableList()
            val records = snapshot.archive.conversations.toMutableList()
            val next = mutableListOf<PendingMutation>()
            fun add(entity: String, id: String, value: JsonElement?) {
                next += PendingMutation(envelope!!.deviceId, newId(), entity, id, snapshot.versions["$entity:$id"] ?: "0", value)
            }
            val copies = mutableMapOf<String,String>()
            val folderIds = mutableMapOf<String, String>()
            fun keepFolder(id: String): String? {
                folderIds[id]?.let { return it }
                val local = archive.folders.find { it.id == id } ?: return null
                val existing = folders.find { it.name.equals(local.name, true) }
                if (existing != null) { folderIds[id] = existing.id; return existing.id }
                val copy = local.copy(id = newId())
                folders += copy; folderIds[id] = copy.id
                add("folder", copy.id, json.encodeToJsonElement(copy))
                return copy.id
            }
            pending.filter { it.entity == "folder" }.map { it.id }.distinct().forEach { keepFolder(it) }
            for (id in pending.filter { it.entity == "conversation" }.map { it.id }.distinct()) {
                val local = archive.conversations.find { it.id == id } ?: continue
                if (records.any { it == local }) continue
                val copy = local.copy(id = newId(), title = local.title.take(190) + " (이 기기 사본)",
                    folderId = local.folderId?.let { keepFolder(it) })
                records += copy; copies[id] = copy.id
                add("conversation", copy.id, json.encodeToJsonElement(copy))
            }
            val appearance = if (pending.any { it.entity == "settings" }) archive.appearance else snapshot.archive.appearance
            if (appearance != snapshot.archive.appearance) add("settings", "settings", buildJsonObject { put("appearance", appearance.name) })
            val nextDrafts = snapshot.drafts.toMutableMap()
            fun recover(value:JsonObject):String {
                val record = draftConversation(value)
                records += record; add("conversation",record.id,json.encodeToJsonElement(record))
                add("draft",record.id,value); nextDrafts[record.id] = value
                return record.id
            }
            val localScopes = pending.filter { it.entity == "draft" }.map { it.id }.toSet() + copies.keys.filter { drafts[it] != null }
            for (id in localScopes) {
                val value = drafts[id]
                val serverDraft = snapshot.drafts[id]
                val target = copies[id] ?: id
                if (value != null && target == id && serverDraft != null && value != serverDraft) {
                    // Both drafts survive; the local one becomes a separate active conversation.
                    copies[id] = recover(value)
                } else if (target != id && value != null) {
                    add("draft",target,value); nextDrafts[target] = value
                    if (serverDraft == value) { add("draft",id,null); nextDrafts.remove(id) }
                } else {
                    if (value != serverDraft) add("draft",id,value)
                    if (value == null) nextDrafts.remove(id) else nextDrafts[id] = value
                }
            }
            // A remote end/delete must not hide an otherwise intact device draft.
            for ((id,value) in nextDrafts.toMap()) {
                if (id == "new" || value["text"]?.jsonPrimitive?.content.orEmpty().isEmpty() || records.any { it.id == id && !it.ended }) continue
                val recovered = recover(value)
                if (id !in copies) copies[id] = recovered
                // Coalesce this operation's replacement mutation for the original draft.
                next.removeAll { it.entity == "draft" && it.id == id }
                if (snapshot.drafts[id] != null) add("draft",id,null)
                nextDrafts.remove(id)
            }
            val accounts = if (next.isEmpty()) envelope!!.accounts - key(user) else envelope!!.accounts + (key(user) to next)
            persist(envelope!!.copy(accounts = accounts))
            volatileQueues.remove(key(user))
            versions = snapshot.versions; drafts = nextDrafts; organizationJobs = snapshot.organizationJobs
            archive = snapshot.archive.copy(conversations = records, folders = folders, appearance = appearance)
            SyncWorkspace(archive,copies)
        }
    }
    fun close() { remote.close() }
    suspend fun flush() = uploadMutex.withLock upload@{
        val user = mutex.withLock { identity } ?: return@upload
        while (true) {
            val first = mutex.withLock {
                if(identity != user) null else {
                    volatileQueues[key(user)]?.let { enqueue(it, user) }
                    queue(user).firstOrNull()
                }
            } ?: break
            // Keep the immutable mutation until ACK. A timeout may follow a successful commit.
            // Network I/O must never hold the lock needed for saving the next input.
            val receipt = remote.mutate(user, first)
            check(receipt.entity == first.entity && receipt.id == first.id && receipt.version.toLong() == first.baseVersion.toLong() + 1)
            mutex.withLock {
                val remaining = queue(user).filterNot { it.mutationId == first.mutationId }
                enqueue(remaining, user)
                if(identity == user) versions = versions + ("${first.entity}:${first.id}" to receipt.version)
            }
        }
    }
}
