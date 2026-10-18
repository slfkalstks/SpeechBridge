package kc.ac.uc.speechbridge

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class DraftRecoveryTest {
    private val user = SyncIdentity("https://sync.example.invalid","a")
    private val original = Conversation(id="original",title="회의",translate=true,record=true,first=Language.KO,second=Language.EN)
    private val draft = buildJsonObject {put("text","아직 제출하지 않은 초안");put("language","KO");put("first","KO");put("second","EN")}
    private class Remote(var data:DataSnapshot):SyncRemote {
        var offline = false
        override suspend fun snapshot(identity:SyncIdentity,deviceId:String):DataSnapshot {if(offline) error("offline");return data}
        override suspend fun mutate(identity:SyncIdentity,mutation:PendingMutation):MutationReceipt = error("Not used in these recovery tests")
    }
    @Test fun conflictingConversationCopyOwnsItsLocalDraft() = runTest {
        val remote = Remote(DataSnapshot(Archive(1,listOf(original)),mapOf("conversation:original" to "1"),"1"))
        val repo = SyncRepository(MemoryCredentialStore(),remote);repo.open(user)
        repo.save(Archive(1,listOf(original.copy(title="내 수정"))));repo.saveDraft(original.id,draft)
        remote.data=remote.data.copy(archive=Archive(1,listOf(original.copy(title="서버 수정",ended=true))),versions=mapOf("conversation:original" to "2"))
        val result=repo.resolveKeepingBoth(user)
        val copy=result.archive.conversations.single {it.id!=original.id}
        assertEquals(copy.id,result.copies[original.id])
        assertEquals(draft,repo.currentDrafts()[copy.id]);assertNull(repo.currentDrafts()[original.id])
        assertFalse(copy.ended)
    }
    @Test fun refreshingAnEndedConversationRecoversItsDraftIntoANewActiveConversationOnce() = runTest {
        val remote=Remote(DataSnapshot(Archive(1,listOf(original.copy(ended=true))),mapOf("conversation:original" to "2","draft:original" to "1"),"2",mapOf("original" to draft)))
        val secure=MemoryCredentialStore();val repo=SyncRepository(secure,remote)
        val archive=repo.open(user)
        val recovered=archive.conversations.single {it.id!=original.id}
        assertFalse(recovered.ended);assertTrue(recovered.utterances.isEmpty())
        assertEquals(draft,repo.currentDrafts()[recovered.id]);assertNull(repo.currentDrafts()[original.id])
        val pending=repo.pendingCount();assertTrue(pending>0)
        val restarted=SyncRepository(secure,remote)
        assertEquals(2,restarted.open(user).conversations.size)
        assertEquals(pending,restarted.pendingCount());assertEquals(draft,restarted.currentDrafts()[recovered.id])
    }
    @Test fun missingRecordDraftIsPreservedAndRecoveredWithoutRecreatingTheDeletedId() = runTest {
        val remote=Remote(DataSnapshot(Archive(1,emptyList()),mapOf("conversation:original" to "3","draft:original" to "1"),"3",mapOf("original" to draft)))
        val repo=SyncRepository(MemoryCredentialStore(),remote)
        val recovered=repo.open(user).conversations.single()
        assertNotEquals(original.id,recovered.id);assertEquals(draft,repo.currentDrafts()[recovered.id])
    }
    @Test fun offlineDraftWithoutItsServerRecordWaitsForConnectionWithoutLosingPendingData() = runTest {
        val remote=Remote(DataSnapshot(Archive(1,listOf(original)),mapOf("conversation:original" to "1"),"1"))
        val secure=MemoryCredentialStore();val repo=SyncRepository(secure,remote);repo.open(user);repo.saveDraft(original.id,draft)
        val saved=secure.read();remote.offline=true
        val restarted=SyncRepository(secure,remote)
        assertEquals("offline_draft_context",assertFailsWith<BridgeRequestException>{restarted.open(user)}.code)
        assertEquals(saved,secure.read());remote.offline=false
        assertEquals(original,restarted.open(user).conversations.single())
        assertEquals(draft,restarted.currentDrafts()[original.id])
    }
}
