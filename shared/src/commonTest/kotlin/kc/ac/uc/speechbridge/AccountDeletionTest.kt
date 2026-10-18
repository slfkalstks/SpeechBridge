package kc.ac.uc.speechbridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AccountDeletionTest {
    private val origin="https://auth.example.invalid"
    private val identity=SyncIdentity(origin,"one")
    private val password="Synthetic-password-123"
    private val session="""{"token":"synthetic-session","expiresAt":10000,"user":{"id":"one","username":"student_1"}}"""
    private val gone="""{"error":{"code":"account_deleted"}}"""
    @Test fun deletionWaitsForDurableLocalCleanupAndNeverStoresThePassword()=runTest {
        val store=MemoryCredentialStore()
        val account=AccountManager(origin,store,client=HttpClient(MockEngine {req->
            if(req.url.encodedPath=="/auth/delete")respond("""{"deleted":true}""") else respond(session)
        }),now={1})
        account.authenticate("student_1",password,false);account.deleteAccount(password)
        assertEquals(AccountDeletion.CONFIRMED,account.state.value.deletion)
        assertFalse(store.read()!!.contains(password))
        assertFailsWith<BridgeRequestException>{account.connection()}
        account.finishDeletion(identity.copy(userId="someone_else"))
        assertNotNull(account.state.value.user)
        account.finishDeletion(identity)
        assertNull(account.state.value.user);assertNull(store.read());account.close()
    }
    @Test fun lostResponseIsRecoveredOnRestartWithoutRememberingPasswordOrUploadingAgain()=runTest {
        val store=MemoryCredentialStore()
        val first=AccountManager(origin,store,client=HttpClient(MockEngine {req->
            if(req.url.encodedPath=="/auth/delete")error("response lost") else respond(session)
        }),now={1})
        first.authenticate("student_1",password,false);first.deleteAccount(password)
        assertEquals(AccountDeletion.REQUESTED,first.state.value.deletion);first.close()
        val paths=mutableListOf<String>()
        val restored=AccountManager(origin,store,client=HttpClient(MockEngine {req->
            paths+=req.url.encodedPath;respond(gone,HttpStatusCode.Unauthorized)
        }),now={20_000})
        restored.restore()
        assertEquals(AccountDeletion.CONFIRMED,restored.state.value.deletion)
        assertEquals(listOf("/auth/me"),paths)
        restored.finishDeletion(identity);assertNull(store.read());restored.close()
    }
    @Test fun incorrectPasswordKeepsTheAccountAndClearFailureRemainsRetryable()=runTest {
        val store=object:CredentialStore {
            var value:String?=null;var failClear=false
            override suspend fun read()=value
            override suspend fun write(value:String?){if(value==null && failClear)error("locked");this.value=value}
        }
        var wrong=true
        val account=AccountManager(origin,store,client=HttpClient(MockEngine {req->
            if(req.url.encodedPath!="/auth/delete")respond(session)
            else if(wrong)respond("""{"error":{"code":"password_incorrect"}}""",HttpStatusCode.Forbidden)
            else respond("""{"deleted":true}""")
        }),now={1})
        account.authenticate("student_1",password,false);account.deleteAccount(password)
        assertEquals(AccountDeletion.NONE,account.state.value.deletion);assertNotNull(account.state.value.user)
        wrong=false;account.deleteAccount(password);store.failClear=true;account.finishDeletion(identity)
        assertEquals(AccountDeletion.CONFIRMED,account.state.value.deletion);assertNotNull(store.read())
        store.failClear=false;account.finishDeletion(identity);assertNull(store.read());account.close()
    }
    @Test fun unsafeMarkerWriteNeverSendsDeletionAndNormalExpiryNeverClaimsDeletion()=runTest {
        val store=object:CredentialStore {
            var value:String?=null;var fail=false
            override suspend fun read()=value
            override suspend fun write(value:String?){if(fail)error("locked");this.value=value}
        }
        val paths=mutableListOf<String>()
        val account=AccountManager(origin,store,client=HttpClient(MockEngine {req->paths+=req.url.encodedPath;respond(session)}),now={1})
        account.authenticate("student_1",password,false);store.fail=true;account.deleteAccount(password)
        assertEquals(listOf("/auth/login"),paths);assertEquals(AccountDeletion.NONE,account.state.value.deletion)
        store.fail=false;account.expired("synthetic-session")
        assertEquals(AccountDeletion.NONE,account.state.value.deletion);assertNull(account.state.value.user);account.close()
    }
    @Test fun remoteDeletionBlocksUseAndAuthenticationUntilLocalCleanupFinishes()=runTest {
        val account=AccountManager(origin,MemoryCredentialStore(),client=HttpClient(MockEngine {respond(session)}),now={1})
        account.authenticate("student_1",password,false)
        account.expired("obsolete-token",deleted=true);assertEquals(AccountDeletion.NONE,account.state.value.deletion)
        account.expired("synthetic-session",deleted=true)
        assertEquals(AccountDeletion.CONFIRMED,account.state.value.deletion)
        account.authenticate("other_user",password,false)
        assertEquals("one",account.state.value.user?.id)
        account.logout();assertEquals(AccountDeletion.CONFIRMED,account.state.value.deletion)
        account.close()
    }
}
