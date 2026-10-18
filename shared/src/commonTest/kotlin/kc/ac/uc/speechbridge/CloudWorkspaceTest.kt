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
        var raw = ArchiveCodec.encode(listOf(Conversation(id="legacy",title="기존 기기 기록",translate=false,record=true,first=Language.KO,second=Language.EN,ended=true)))
        override suspend fun read() = raw
        override suspend fun write(text: String) { writes++; raw=text }
    }
    private class Remote : SyncRemote {
        var offline = false
        val sent = mutableListOf<SyncIdentity>()
        val draftValues = mutableListOf<JsonObject>()
        val drafts = mutableMapOf<String,MutableMap<String,JsonObject>>()
        val archives = mutableMapOf<String,Archive>()
        val versions = mutableMapOf<String,MutableMap<String,String>>()
        override suspend fun snapshot(identity: SyncIdentity, deviceId: String): DataSnapshot {
            if(offline) error("offline")
            return DataSnapshot(archives[identity.userId] ?: Archive(1,emptyList()),versions[identity.userId].orEmpty().toMap(),"0",drafts[identity.userId].orEmpty().toMap())
        }
        override suspend fun mutate(identity: SyncIdentity, mutation: PendingMutation): MutationReceipt {
            if(offline) error("offline")
            if (mutation.entity == "draft" && mutation.value != null) {
                val value = mutation.value.jsonObject
                require(listOf("language","first","second").all { value[it]?.jsonPrimitive?.content in setOf("KO","EN","JA") })
                draftValues += value
            }
            sent += identity
            if ((versions[identity.userId]?.get("${mutation.entity}:${mutation.id}") ?: "0") != mutation.baseVersion)
                throw BridgeRequestException("version_conflict","conflict")
            val rev = (mutation.baseVersion.toLong()+1).toString()
            versions.getOrPut(identity.userId) { mutableMapOf() }["${mutation.entity}:${mutation.id}"] = rev
            if(mutation.entity == "conversation") {
                val old = archives[identity.userId] ?: Archive(1,emptyList())
                archives[identity.userId] = old.copy(conversations = old.conversations.filterNot { it.id==mutation.id } + listOfNotNull(mutation.value?.let { ArchiveCodec.json.decodeFromJsonElement<Conversation>(it) }))
            }
            if(mutation.entity == "draft") {
                val values = drafts.getOrPut(identity.userId) { mutableMapOf() }
                if(mutation.value == null) values.remove(mutation.id) else values[mutation.id]=mutation.value.jsonObject
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
    @Test fun sessionEndingDuringSecureWriteDoesNotDropTheFollowingInput() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore(); val account = account(); val remote = Remote()
        val backing = MemoryCredentialStore()
        var gate: CompletableDeferred<Unit>? = null
        val secure = object : CredentialStore {
            override suspend fun read() = backing.read()
            override suspend fun write(value: String?) { gate?.await(); backing.write(value) }
        }
        val vm = BridgeViewModel(Legacy(), account=account, sync=SyncRepository(secure,remote)); owner.put("vm",vm)
        try {
            account.authenticate("student_a","Synthetic-password-123!",false); runCurrent()
            remote.offline = true; gate = CompletableDeferred()
            vm.start("쓰기 중 만료",false,true,Language.KO,Language.EN); runCurrent()
            vm.addUtterance(vm.state.value.activeId!!,Language.KO,"마지막 입력도 보존해야 합니다.")
            account.expired("synthetic-student_a"); runCurrent()
            assertTrue(vm.state.value.conversations.isEmpty())
            gate.complete(Unit); runCurrent()
            assertTrue(backing.read()!!.contains("마지막 입력도 보존해야 합니다."))
            assertTrue(vm.state.value.accountRequired)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun failedStorageThenExpiryRecoversInputAfterSameAccountLogsIn() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner=ViewModelStore(); val account=account(); val remote=Remote()
        val backing=MemoryCredentialStore(); var locked=false
        val secure=object : CredentialStore {
            override suspend fun read()=backing.read()
            override suspend fun write(value: String?) { if(locked) error("locked"); backing.write(value) }
        }
        val vm=BridgeViewModel(Legacy(),account=account,sync=SyncRepository(secure,remote)); owner.put("vm",vm)
        try {
            account.authenticate("student_a","Synthetic-password-123!",false); runCurrent()
            locked=true
            vm.start("복구할 대화",false,true,Language.KO,Language.EN); runCurrent()
            vm.addUtterance(vm.state.value.activeId!!,Language.KO,"저장 실패 중 입력"); runCurrent()
            assertFalse(vm.prepareAccountChange())
            account.expired("synthetic-student_a"); runCurrent()
            assertTrue(vm.state.value.conversations.isEmpty())
            locked=false
            account.authenticate("student_b","Synthetic-password-123!",false); runCurrent()
            assertTrue(vm.state.value.conversations.isEmpty())
            account.logout(); runCurrent()
            account.authenticate("student_a","Synthetic-password-123!",false); runCurrent()
            assertEquals("저장 실패 중 입력",vm.state.value.conversations.single().utterances.single().text)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun draftLanguageUsesTheServerContractAndSurvivesAnEarlierWriteFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner=ViewModelStore(); val account=account(); val remote=Remote()
        val backing=MemoryCredentialStore(); var locked=false
        val secure=object : CredentialStore {
            override suspend fun read()=backing.read()
            override suspend fun write(value: String?) { if(locked) error("locked"); backing.write(value) }
        }
        val vm=BridgeViewModel(Legacy(),account=account,sync=SyncRepository(secure,remote)); owner.put("vm",vm)
        try {
            account.authenticate("student_a","Synthetic-password-123!",false); runCurrent()
            locked=true
            vm.start("초안 복구",false,true,Language.JA,Language.KO); runCurrent()
            val id=vm.state.value.activeId!!
            vm.updateInputDraft(id,UtteranceDraft(Language.JA,"아직 발화 전")); runCurrent()
            account.expired("synthetic-student_a"); runCurrent(); locked=false
            account.authenticate("student_a","Synthetic-password-123!",false); runCurrent()
            assertEquals("아직 발화 전",vm.state.value.inputDrafts[id]?.text)
            assertEquals(0,vm.state.value.syncPending)
            assertEquals("JA",remote.draftValues.last()["first"]?.jsonPrimitive?.content)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun legacyImportIsExplicitAndOnlyClearsTheSourceAfterServerVerification() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner=ViewModelStore(); val account=account(); val remote=Remote(); val legacy=Legacy()
        val vm=BridgeViewModel(legacy,account=account,sync=SyncRepository(MemoryCredentialStore(),remote)); owner.put("vm",vm)
        try {
            account.authenticate("student_a","Synthetic-password-123!",false); runCurrent()
            assertEquals(1,vm.state.value.legacyCount); assertTrue(remote.sent.isEmpty())
            remote.offline=true; vm.importLegacyRecords(); runCurrent()
            assertEquals(0,legacy.writes)
            remote.offline=false; vm.importLegacyRecords(); runCurrent()
            assertEquals("기존 기기 기록",remote.archives["student_a"]!!.conversations.single().title)
            assertTrue(ArchiveCodec.decodeArchive(legacy.raw).conversations.isEmpty())
            assertEquals(0,vm.state.value.legacyCount)
            vm.importLegacyRecords(); runCurrent()
            assertEquals(1,remote.archives["student_a"]!!.conversations.size)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun legacyImportCannotReplaceADifferentServerRecordWithTheSameId() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner=ViewModelStore(); val account=account(); val remote=Remote(); val legacy=Legacy()
        val original=ArchiveCodec.decodeArchive(legacy.raw).conversations.single().copy(title="서버에서 수정한 내용")
        remote.archives["student_a"]=Archive(1,listOf(original))
        remote.versions["student_a"]=mutableMapOf("conversation:legacy" to "2")
        val vm=BridgeViewModel(legacy,account=account,sync=SyncRepository(MemoryCredentialStore(),remote)); owner.put("vm",vm)
        try {
            account.authenticate("student_a","Synthetic-password-123!",false); runCurrent()
            vm.importLegacyRecords(); runCurrent()
            assertEquals(0,legacy.writes); assertTrue(remote.sent.isEmpty())
            assertEquals(original,remote.archives["student_a"]!!.conversations.single())
            assertNotNull(vm.state.value.message)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun explicitConflictResolutionPreservesServerAndLocalVersionsSeparately() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner=ViewModelStore(); val account=account(); val remote=Remote()
        val original=Conversation(id="conflict",title="원래 제목",translate=false,record=true,first=Language.KO,second=Language.EN,ended=true)
        remote.archives["student_a"]=Archive(1,listOf(original)); remote.versions["student_a"]=mutableMapOf("conversation:conflict" to "1")
        val vm=BridgeViewModel(Legacy(),account=account,sync=SyncRepository(MemoryCredentialStore(),remote)); owner.put("vm",vm)
        try {
            account.authenticate("student_a","Synthetic-password-123!",false); runCurrent()
            remote.archives["student_a"]=Archive(1,listOf(original.copy(title="다른 기기의 제목",metadataRevision=1)))
            remote.versions["student_a"]!!["conversation:conflict"]="2"
            vm.classify("conflict","내가 쓴 제목",Category.DAILY,null,null); runCurrent()
            assertTrue(vm.state.value.syncConflict)
            vm.resolveSyncConflict(); runCurrent()
            val records=remote.archives["student_a"]!!.conversations
            assertEquals(2,records.size)
            assertEquals("다른 기기의 제목",records.single { it.id=="conflict" }.title)
            assertTrue(records.single { it.id!="conflict" }.title.startsWith("내가 쓴 제목"))
            assertFalse(vm.state.value.syncConflict); assertEquals(0,vm.state.value.syncPending)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun connectionRecoveryAutomaticallyUploadsDurablePendingInput() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner=ViewModelStore(); val account=account(); val remote=Remote()
        val vm=BridgeViewModel(Legacy(),account=account,sync=SyncRepository(MemoryCredentialStore(),remote)); owner.put("vm",vm)
        try {
            account.authenticate("student_a","Synthetic-password-123!",false); runCurrent()
            remote.offline=true
            vm.start("자동 복구",false,true,Language.KO,Language.EN); runCurrent()
            assertTrue(vm.state.value.syncPending>0)
            remote.offline=false
            advanceTimeBy(15_001); runCurrent()
            assertEquals(0,vm.state.value.syncPending)
            assertEquals("자동 복구",remote.archives["student_a"]!!.conversations.single().title)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun keepingBothSelectsTheLocalCopyAndMovesItsDraftBeforeAnotherEdit() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner=ViewModelStore();val account=account();val remote=Remote()
        val original=Conversation(id="active",title="진행 중",translate=false,record=true,first=Language.KO,second=Language.EN)
        remote.archives["student_a"]=Archive(1,listOf(original));remote.versions["student_a"]=mutableMapOf("conversation:active" to "1")
        val vm=BridgeViewModel(Legacy(),account=account,sync=SyncRepository(MemoryCredentialStore(),remote));owner.put("vm",vm)
        try {
            account.authenticate("student_a","Synthetic-password-123!",false);runCurrent()
            remote.offline=true
            vm.addUtterance("active",Language.KO,"내 문장");vm.updateInputDraft("active",UtteranceDraft(text="작성 중인 초안"));runCurrent()
            remote.archives["student_a"]=Archive(1,listOf(original.copy(title="서버 종료",ended=true)))
            remote.versions["student_a"]!!["conversation:active"]="2";remote.offline=false
            vm.retrySync();runCurrent();assertTrue(vm.state.value.syncConflict)
            vm.resolveSyncConflict();runCurrent()
            val copy=vm.state.value.conversations.single {it.id!="active"}
            assertEquals(copy.id,vm.state.value.activeId);assertEquals(copy.id,vm.state.value.selectedId)
            assertEquals("작성 중인 초안",vm.state.value.inputDrafts[copy.id]?.text)
            assertNull(vm.state.value.inputDrafts["active"])
            vm.addUtterance(copy.id,Language.KO,"다음 문장");runCurrent()
            assertEquals(2,remote.archives["student_a"]!!.conversations.single {it.id==copy.id}.utterances.size)
        } finally {owner.clear();Dispatchers.resetMain()}
    }
    @Test fun refreshLoadsTheLatestServerDraftInsteadOfOverwritingItWithStaleUiText() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner=ViewModelStore();val account=account();val remote=Remote()
        val vm=BridgeViewModel(Legacy(),account=account,sync=SyncRepository(MemoryCredentialStore(),remote));owner.put("vm",vm)
        try {
            account.authenticate("student_a","Synthetic-password-123!",false);runCurrent()
            vm.updateIdleDraft(UtteranceDraft(text="이전 입력"));runCurrent()
            remote.drafts["student_a"]!!["new"]=buildJsonObject {put("text","다시 불러온 입력");put("language","JA");put("first","JA");put("second","KO")}
            remote.versions["student_a"]!!["draft:new"]="2"
            vm.refreshRecords();runCurrent()
            assertEquals("다시 불러온 입력",vm.state.value.idleDraft.text)
            assertEquals(Language.JA,vm.state.value.startDraft.first)
            vm.setAppearance(Appearance.DARK);runCurrent()
            assertEquals("다시 불러온 입력",remote.drafts["student_a"]!!["new"]!!["text"]!!.jsonPrimitive.content)
        } finally {owner.clear();Dispatchers.resetMain()}
    }
    @Test fun selectingAnActiveConversationFromAnotherDeviceMakesItTheInputTarget() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner=ViewModelStore();val account=account();val remote=Remote()
        val first=Conversation(id="first",title="첫 대화",translate=false,record=true,first=Language.KO,second=Language.EN)
        remote.archives["student_a"]=Archive(1,listOf(first,first.copy(id="second",title="다른 대화")))
        val vm=BridgeViewModel(Legacy(),account=account,sync=SyncRepository(MemoryCredentialStore(),remote));owner.put("vm",vm)
        try {
            account.authenticate("student_a","Synthetic-password-123!",false);runCurrent()
            assertEquals("second",vm.state.value.activeId)
            vm.select("first");assertEquals("first",vm.state.value.activeId);assertEquals("first",vm.state.value.selectedId)
        }finally{owner.clear();Dispatchers.resetMain()}
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
