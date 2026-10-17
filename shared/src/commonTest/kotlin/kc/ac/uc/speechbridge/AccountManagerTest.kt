package kc.ac.uc.speechbridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AccountManagerTest {
    private val session = """{"token":"synthetic-session","expiresAt":10000,"user":{"id":"one","username":"student_1","role":"member","capabilities":["translate","organize","extract"]}}"""
    private val me = """{"user":{"id":"one","username":"student_1","role":"member","capabilities":["translate","organize","extract"]}}"""
    private val origin = "https://auth.example.invalid"
    private val password = "Synthetic-password-123"
    @Test fun registerPersistsOnlySessionAndRestoresAutomatically() = runTest {
        val store = MemoryCredentialStore()
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            if(request.url.encodedPath == "/auth/me") {
                assertEquals("Bearer synthetic-session", request.headers[HttpHeaders.Authorization])
                respond(me)
            } else respond(session)
        })
        val account = AccountManager(origin, store, client = client, now = { 100 })
        account.authenticate("STUDENT_1", password, true)
        assertEquals("student_1", account.state.value.user?.username)
        assertFalse(store.read()!!.contains(password))
        val restored = AccountManager(origin, store, client = client, now = { 200 })
        restored.restore()
        assertEquals(Connection(origin, "synthetic-session"), restored.connection())
        assertEquals(listOf("/auth/register", "/auth/me"), paths)
        restored.close()
    }
    @Test fun noLoginNeverCreatesGuestAndDoesNotSendInferenceCredentials() = runTest {
        val account = AccountManager(origin, MemoryCredentialStore(), client = HttpClient(MockEngine { error("No request expected") }))
        account.restore()
        assertNull(account.state.value.user)
        assertEquals("login_required", assertFailsWith<BridgeRequestException> { account.connection() }.code)
        account.close()
    }
    @Test fun endpointChangeAndExpiryNeverForwardOldCredentials() = runTest {
        for (expired in listOf(true, false)) {
            val store = MemoryCredentialStore()
            val writer = AccountManager(origin, store, client = HttpClient(MockEngine { respond(session) }), now = { 1 })
            writer.authenticate("student_1", password, false); writer.close()
            val restored = AccountManager(if(expired) origin else "https://other.example.invalid", store,
                client = HttpClient(MockEngine { error("Must not disclose credentials") }), now = { if(expired) 10001 else 1 })
            restored.restore()
            assertNull(restored.state.value.user); assertNull(store.read())
            restored.close()
        }
    }
    @Test fun logoutRevokesThenClearsCredentialAndLateOld401CannotRemoveNewLogin() = runTest {
        val store = MemoryCredentialStore()
        val paths = mutableListOf<String>()
        val account = AccountManager(origin, store, client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            respond(if(request.url.encodedPath == "/auth/logout") "{}" else session)
        }), now = { 1 })
        account.authenticate("student_1", password, false)
        account.expired("old-token")
        assertNotNull(account.state.value.user)
        account.logout()
        assertNull(account.state.value.user); assertNull(store.read())
        assertEquals(listOf("/auth/login", "/auth/logout"), paths)
        account.close()
    }
    @Test fun invalidCredentialsRejectedBeforeNetworkAndErrorsAreSafe() = runTest {
        val account = AccountManager(origin, MemoryCredentialStore(), client = HttpClient(MockEngine {
            respond("""{"error":{"code":"login_failed","message":"sensitive internal detail"}}""", HttpStatusCode.Unauthorized)
        }), now = { 1 })
        account.authenticate("x", "short", true)
        assertTrue(account.state.value.error!!.contains("12~128"))
        account.authenticate("student_1", password, false)
        assertEquals("아이디 또는 비밀번호를 확인해 주세요.", account.state.value.error)
        assertNull(account.state.value.user)
        account.close()
    }
    @Test fun storageFailureRevokesUnpersistedSession() = runTest {
        val paths = mutableListOf<String>()
        val brokenStore = object : CredentialStore {
            override suspend fun read(): String? = null
            override suspend fun write(value: String?) { error("Disk unavailable") }
        }
        val account = AccountManager(origin, brokenStore, client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath; respond(if(request.url.encodedPath == "/auth/logout") "{}" else session)
        }), now = { 1 })
        account.authenticate("student_1", password, false)
        assertNull(account.state.value.user)
        assertEquals(listOf("/auth/login", "/auth/logout"), paths)
        assertTrue(account.state.value.error!!.contains("저장하지 못"))
        account.close()
    }
    @Test fun logoutCannotBeOvertakenByPendingLogin() = runTest {
        val pending = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        val store = MemoryCredentialStore()
        val account = AccountManager(origin, store, client = HttpClient(MockEngine { request ->
            if(request.url.encodedPath == "/auth/login") { reached.complete(Unit); pending.await(); respond(session) }
            else respond("{}")
        }), now = { 1 })
        val login = async { account.authenticate("student_1", password, false) }
        reached.await()
        val logout = async { account.logout() }
        pending.complete(Unit)
        login.await(); logout.await()
        assertNull(account.state.value.user); assertNull(store.read())
        account.close()
    }
    @Test fun releaseRejectsHttpBeforeAnyRequest() {
        assertFailsWith<IllegalArgumentException> { AccountManager("http://localhost:8787", MemoryCredentialStore()) }
    }
}
