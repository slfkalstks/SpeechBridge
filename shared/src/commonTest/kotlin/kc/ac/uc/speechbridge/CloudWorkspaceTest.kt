@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package kc.ac.uc.speechbridge

import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CloudWorkspaceTest {
    private class Legacy : SessionStore {
        var writes = 0
        override suspend fun read() = ArchiveCodec.encode(listOf(Conversation(id="legacy",title="기존 기기 기록",translate=false,record=true,first=Language.KO,second=Language.EN)))
        override suspend fun write(text: String) { writes++ }
    }
    private class Remote : SyncRemote {
        var offline = false
        val sent = mutableListOf<SyncIdentity>()
        val archives = mutableMapOf<String,Archive>()
        val versions = mutableMapOf<String,MutableMap<String,String>>()
        override suspend fun snapshot(identity: SyncIdentity, deviceId: String): DataSnapshot {
            if(offline) error("offline")
            return DataSnapshot(archives[identity.userId] ?: Archive(1,emptyList()),versions[identity.userId].orEmpty(),"0")
        }
        override suspend fun mutate(identity: SyncIdentity, mutation: PendingMutation): MutationReceipt {
            if(offline) error("offline")
            sent += identity
            val rev = (mutation.baseVersion.toLong()+1).toString()
            versions.getOrPut(identity.userId) { mutableMapOf() }["${mutation.entity}:${mutation.id}"] = rev
            if(mutation.entity == "conversation") {
                val old = archives[identity.userId] ?: Archive(1,emptyList())
                archives[identity.userId] = old.copy(conversations = old.conversations.filterNot { it.id==mutation.id } + listOfNotNull(mutation.value?.let { ArchiveCodec.json.decodeFromJsonElement<Conversation>(it) }))
            }
            return MutationReceipt(mutation.entity,mutation.id,rev,rev)
        }
    }
    @Test fun loginGatesRecordsAndOfflineChangesNeverReachTheLegacyArchive() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore(); val legacy = Legacy(); val secure = MemoryCredentialStore(); val remote = Remote()
        val account = account(); val repo = SyncRepository(secure,remote)
        val vm = BridgeViewModel(legacy,account=account,sync=repo)
        owner.put("vm",vm)
        try {
            account.restore(); runCurrent()
            assertTrue(vm.state.value.accountRequired)
            vm.start("금지",false,true,Language.KO,Language.EN); runCurrent()
            assertTrue(vm.state.value.conversations.isEmpty())
            account.authenticate("student_a","Synthetic-password-123!",false); runCurrent()
            assertFalse(vm.state.value.accountRequired)
            assertTrue(vm.state.value.conversations.isEmpty(),"기존 기기 기록의 소유자를 추정하면 안 됩니다.")
            remote.offline=true
            vm.start("내 기록",false,true,Language.KO,Language.EN); runCurrent()
            val id=vm.state.value.activeId!!
            vm.addUtterance(id,Language.KO,"내일까지 문서를 보내겠습니다."); runCurrent()
            assertFalse(vm.state.value.unsaved)
            assertTrue(vm.state.value.syncPending>0)
            assertTrue(secure.read()!!.contains("내일까지 문서를"))
            assertEquals(0,legacy.writes)
            remote.offline=false
            vm.retrySync(); runCurrent()
            assertEquals(0,vm.state.value.syncPending)
            assertFalse(secure.read()!!.contains("내일까지 문서를"))
            assertEquals("내 기록",remote.archives["student_a"]!!.conversations.single().title)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun logoutClearsVisibleDataAndAnotherAccountCannotFlushThePendingQueue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner=ViewModelStore(); val account=account(); val remote=Remote(); val secure=MemoryCredentialStore()
        val vm=BridgeViewModel(Legacy(),account=account,sync=SyncRepository(secure,remote)); owner.put("vm",vm)
        try {
            account.authenticate("student_a","Synthetic-password-123!",false); runCurrent()
            remote.offline=true
            vm.start("A 개인 기록",false,true,Language.KO,Language.EN); runCurrent()
            assertTrue(vm.prepareAccountChange())
            account.logout(); runCurrent()
            assertTrue(vm.state.value.conversations.isEmpty()); assertTrue(vm.state.value.accountRequired)
            remote.offline=false
            account.authenticate("student_b","Synthetic-password-123!",false); runCurrent()
            vm.retrySync(); runCurrent()
            assertTrue(vm.state.value.conversations.isEmpty())
            assertTrue(remote.sent.isEmpty())
            assertTrue(secure.read()!!.contains("A 개인 기록"))
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun draftInputIsDurablyQueuedWithoutMakingItAnUtterance() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner=ViewModelStore(); val account=account(); val remote=Remote(); val secure=MemoryCredentialStore()
        val vm=BridgeViewModel(Legacy(),account=account,sync=SyncRepository(secure,remote)); owner.put("vm",vm)
        try {
            account.authenticate("student_a","Synthetic-password-123!",false); runCurrent(); remote.offline=true
            vm.updateIdleDraft(UtteranceDraft(text="보내기 전 초안")); runCurrent()
            assertTrue(secure.read()!!.contains("보내기 전 초안"))
            assertTrue(vm.state.value.conversations.isEmpty())
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    private fun account() = AccountManager("https://sync.example.invalid",MemoryCredentialStore(),client=HttpClient(MockEngine { request ->
        if(request.url.encodedPath=="/auth/logout") respond("{}")
        else {
            val body = request.body.toByteArray().decodeToString()
            val username = if(body.contains("student_b")) "student_b" else "student_a"
            respond("""{"token":"synthetic-$username","expiresAt":100000,"user":{"id":"$username","username":"$username","role":"member"}}""")
        }
    }),now={1})
}
