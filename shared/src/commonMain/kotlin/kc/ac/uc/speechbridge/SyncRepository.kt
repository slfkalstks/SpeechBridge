package kc.ac.uc.speechbridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class SyncIdentity(val origin: String, val userId: String)
@Serializable data class PendingMutation(val deviceId: String, val mutationId: String, val entity: String,
    val id: String, val baseVersion: String, val value: JsonElement?)
@Serializable data class MutationReceipt(val entity: String, val id: String, val version: String, val cursor: String)
@Serializable data class DataSnapshot(val archive: Archive, val versions: Map<String, String>, val cursor: String,
    val drafts: Map<String, JsonObject> = emptyMap())
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
        identity = null; archive = Archive(1, emptyList()); versions = emptyMap(); drafts = emptyMap()
        val pending = queue(next)
        val snapshot = try { remote.snapshot(next, envelope!!.deviceId) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is BridgeRequestException && e.code in setOf("unauthorized", "login_required")) throw e
            if (pending.isEmpty()) throw e
            // Offline restart recovers only pending data, not a permanent history cache.
            DataSnapshot(Archive(1, emptyList()), pending.associate { "${it.entity}:${it.id}" to it.baseVersion }, "0")
        }
        require(snapshot.archive.version == 1)
        versions = snapshot.versions
        drafts = snapshot.drafts.toMutableMap().apply {
            pending.filter { it.entity == "draft" }.forEach { if(it.value == null) remove(it.id) else put(it.id, it.value.jsonObject) }
        }
        archive = overlay(snapshot.archive, pending)
        identity = next
        archive
    }
    suspend fun closeAccount() = mutex.withLock {
        identity = null; archive = Archive(1, emptyList()); versions = emptyMap(); drafts = emptyMap()
    }
    suspend fun pendingCount(): Int = mutex.withLock { identity?.let { queue(it).size } ?: 0 }
    suspend fun currentDrafts(): Map<String, JsonObject> = mutex.withLock { drafts.toMap() }

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
