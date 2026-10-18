@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package kc.ac.uc.speechbridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlin.test.*

class OrganizationRefreshTest {
    private val user=SyncIdentity("https://example.invalid","user")
    private val record=Conversation(id="c",title="대화",translate=false,record=true,first=Language.KO,second=Language.EN,ended=true)
    private class Remote(var value:DataSnapshot):SyncRemote {
        var gate:CompletableDeferred<Unit>?=null
        override suspend fun snapshot(identity:SyncIdentity,deviceId:String):DataSnapshot {
            val captured=value;gate?.await();return captured
        }
        override suspend fun mutate(identity:SyncIdentity,mutation:PendingMutation)=MutationReceipt(mutation.entity,mutation.id,(mutation.baseVersion.toLong()+1).toString(),"2")
    }
    @Test fun completedJobRefreshUpdatesBaselineWithoutWritingServerHistoryToDevice()=runTest {
        val secure=MemoryCredentialStore()
        val remote=Remote(DataSnapshot(Archive(1,listOf(record)),mapOf("conversation:c" to "1"),"1",organizationJobs=mapOf("c" to "pending")))
        val repo=SyncRepository(secure,remote);repo.open(user)
        assertEquals("pending",repo.currentOrganizationJobs()["c"])
        val completed=record.copy(title="AI 제목",summary="요약",organized=true,organizationAttempted=true,metadataRevision=1)
        remote.value=DataSnapshot(Archive(1,listOf(completed)),mapOf("conversation:c" to "2"),"2",organizationJobs=mapOf("c" to "succeeded"))
        assertEquals(completed,repo.refreshIfIdle(user){true}!!.archive.conversations.single())
        assertEquals("succeeded",repo.currentOrganizationJobs()["c"])
        repo.save(Archive(1,listOf(completed)),user);assertEquals(0,repo.pendingCount())
        assertFalse(secure.read()!!.contains("AI 제목"))
    }
    @Test fun refreshDoesNotBlockSecureWritesOrOverwriteChangesMadeDuringNetworkWait()=runTest {
        val secure=MemoryCredentialStore();val remote=Remote(DataSnapshot(Archive(1,listOf(record)),mapOf("conversation:c" to "1"),"1"))
        val repo=SyncRepository(secure,remote);repo.open(user)
        remote.gate=CompletableDeferred()
        val refresh=async{repo.refreshIfIdle(user){true}};runCurrent()
        repo.save(Archive(1,listOf(record.copy(title="직접 수정"))),user)
        assertTrue(secure.read()!!.contains("직접 수정"))
        remote.gate!!.complete(Unit);assertNull(refresh.await());assertEquals(1,repo.pendingCount())
        assertNull(repo.refreshIfIdle(user){true})
    }
    @Test fun accountChangeAndUnpersistedUiEditRejectLateSnapshots()=runTest {
        val remote=Remote(DataSnapshot(Archive(1,listOf(record)),mapOf("conversation:c" to "1"),"1"))
        val repo=SyncRepository(MemoryCredentialStore(),remote);repo.open(user)
        assertNull(repo.refreshIfIdle(user){false})
        remote.gate=CompletableDeferred()
        val refresh=async{repo.refreshIfIdle(user){true}};runCurrent();repo.closeAccount()
        remote.gate!!.complete(Unit);assertNull(refresh.await());assertTrue(repo.currentOrganizationJobs().isEmpty())
    }
}
