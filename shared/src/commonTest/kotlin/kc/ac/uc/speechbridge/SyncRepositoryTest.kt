package kc.ac.uc.speechbridge

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.*
import kotlin.test.*

class SyncRepositoryTest {
    private val identity = SyncIdentity("https://example.invalid", "account_a")
    private fun archive(title: String = "새 기록") = Archive(1, listOf(Conversation(id = "c1", title = title,
        createdAt = "2026-10-18T00:00:00Z", translate = true, record = true, first = Language.KO, second = Language.EN)))

    private class Remote : SyncRemote {
        var offline = false
        var ambiguous = false
        var conflict = false
        var saved = Archive(1, emptyList())
        val accepted = mutableMapOf<String, MutationReceipt>()
        val sent = mutableListOf<Pair<SyncIdentity, PendingMutation>>()
        var version = 0
        override suspend fun snapshot(identity: SyncIdentity, deviceId: String): DataSnapshot {
            if (offline) error("offline")
            return DataSnapshot(saved, if(version == 0) emptyMap() else mapOf("conversation:c1" to version.toString()), version.toString())
        }
        override suspend fun mutate(identity: SyncIdentity, mutation: PendingMutation): MutationReceipt {
            sent += identity to mutation
            if (offline) error("offline")
            accepted[mutation.mutationId]?.let { return it }
            if (conflict || mutation.baseVersion != version.toString()) throw BridgeRequestException("version_conflict", "conflict")
            version++
            saved = Archive(1, listOf(ArchiveCodec.json.decodeFromJsonElement<Conversation>(mutation.value!!)))
            val receipt = MutationReceipt(mutation.entity, mutation.id, version.toString(), version.toString())
            accepted[mutation.mutationId] = receipt
            if (ambiguous) { ambiguous = false; error("response lost after commit") }
            return receipt
        }
    }
    @Test fun pendingSurvivesRestartAndAckRemovesBusinessDataFromDevice() = runTest {
        val secure = MemoryCredentialStore(); val remote = Remote()
        val repo = SyncRepository(secure, remote)
        repo.open(identity)
        remote.offline = true
        repo.save(archive())
        assertEquals(1, repo.pendingCount())
        assertTrue(secure.read()!!.contains("새 기록")) // Memory test double; production storage is encrypted.
        val restarted = SyncRepository(secure, remote)
        assertEquals("새 기록", restarted.open(identity).conversations.single().title)
        remote.offline = false
        restarted.flush()
        assertEquals(0, restarted.pendingCount())
        assertFalse(secure.read()!!.contains("새 기록"))
        assertEquals("새 기록", SyncRepository(secure, remote).open(identity).conversations.single().title)
    }
    @Test fun retryAfterLostAckUsesExactlyTheSameMutationId() = runTest {
        val remote = Remote(); val repo = SyncRepository(MemoryCredentialStore(), remote)
        repo.open(identity); repo.save(archive())
        remote.ambiguous = true
        assertFailsWith<IllegalStateException> { repo.flush() }
        repo.flush()
        assertEquals(1, remote.accepted.size)
        assertEquals(remote.sent[0].second.mutationId, remote.sent[1].second.mutationId)
        assertEquals(0, repo.pendingCount())
    }
    @Test fun accountSwitchNeverUploadsAnotherAccountsPendingData() = runTest {
        val remote = Remote(); val secure = MemoryCredentialStore(); val repo = SyncRepository(secure, remote)
        repo.open(identity); repo.save(archive())
        repo.open(identity.copy(userId = "account_b"))
        repo.flush()
        assertTrue(remote.sent.isEmpty())
        assertEquals(0, repo.pendingCount())
        repo.open(identity); repo.flush()
        assertEquals(identity, remote.sent.single().first)
    }
    @Test fun serverConflictKeepsLocalEditForExplicitResolution() = runTest {
        val remote = Remote(); val secure = MemoryCredentialStore(); val repo = SyncRepository(secure, remote)
        repo.open(identity); repo.save(archive("사용자 수정")); remote.conflict = true
        assertFailsWith<BridgeRequestException> { repo.flush() }
        assertEquals(1, repo.pendingCount())
        assertTrue(secure.read()!!.contains("사용자 수정"))
    }
    @Test fun secureWriteFailureCannotReportPendingSavedOrReplaceMemoryBaseline() = runTest {
        val secure = object : CredentialStore {
            var data: String? = null; var fail = false
            override suspend fun read() = data
            override suspend fun write(value: String?) { if(fail) error("storage locked"); data = value }
        }
        val repo = SyncRepository(secure, Remote()); repo.open(identity)
        secure.fail = true
        assertFailsWith<IllegalStateException> { repo.save(archive()) }
        secure.fail = false
        repo.save(archive()); assertEquals(1, repo.pendingCount())
    }
    @Test fun draftsAreQueuedAndUnchangedServerHistoryIsNeverCopiedToPendingStorage() = runTest {
        val remote = Remote().apply { saved = archive("서버 전용 기록"); version = 1 }
        val secure = MemoryCredentialStore(); val repo = SyncRepository(secure, remote)
        repo.open(identity)
        repo.save(remote.saved)
        assertEquals(0, repo.pendingCount())
        assertFalse(secure.read()!!.contains("서버 전용 기록"))
        repo.saveDraft("new", buildJsonObject { put("text", "아직 전송 전"); put("language", "KO"); put("first", "KO"); put("second", "EN") })
        assertEquals(1, repo.pendingCount())
        assertTrue(secure.read()!!.contains("아직 전송 전"))
        assertFalse(secure.read()!!.contains("서버 전용 기록"))
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun slowUploadDoesNotBlockDurableStorageOfTheNextUtterance() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val base = Remote()
        val remote = object : SyncRemote by base {
            override suspend fun mutate(identity: SyncIdentity, mutation: PendingMutation): MutationReceipt {
                entered.complete(Unit); release.await(); return base.mutate(identity, mutation)
            }
        }
        val repo = SyncRepository(MemoryCredentialStore(), remote)
        repo.open(identity); repo.save(archive("첫 입력"))
        val upload = async { repo.flush() }; entered.await()
        val next = async { repo.save(archive("다음 입력")) }; runCurrent()
        try { assertTrue(next.isCompleted, "네트워크 대기 중에도 다음 입력은 기기에 먼저 저장되어야 합니다.") }
        finally { release.complete(Unit) }
        next.await(); upload.await()
        assertEquals("다음 입력", base.saved.conversations.single().title)
    }
}
