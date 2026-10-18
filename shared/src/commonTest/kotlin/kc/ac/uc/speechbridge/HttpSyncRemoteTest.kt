package kc.ac.uc.speechbridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import io.ktor.http.content.TextContent
import kotlin.test.*

class HttpSyncRemoteTest {
    private val origin = "https://sync.example.invalid"
    private val session = """{"token":"synthetic-account-token","expiresAt":10000,"user":{"id":"user_a","username":"student_a","role":"member"}}"""
    @Test fun authorizedSnapshotUsesAccountTokenAndParsesDatabaseArchive() = runTest {
        val account = AccountManager(origin, MemoryCredentialStore(), client = HttpClient(MockEngine { respond(session) }), now = { 1 })
        account.authenticate("student_a", "Synthetic-password-123!", false)
        val remote = HttpSyncRemote(account, HttpClient(MockEngine { request ->
            assertEquals(origin + "/data/snapshot", request.url.toString())
            assertEquals("Bearer synthetic-account-token", request.headers[HttpHeaders.Authorization])
            respond("""{"archive":{"version":1,"conversations":[],"folders":[],"appearance":"DARK"},"versions":{},"cursor":"0","drafts":{}}""")
        }))
        assertEquals(Appearance.DARK, remote.snapshot(SyncIdentity(origin,"user_a"),"install_1").archive.appearance)
        remote.close(); account.close()
    }
    @Test fun platformMetadataAccompaniesSnapshotAndPendingMutationWithoutChangingItsId() = runTest {
        val account = AccountManager(origin, MemoryCredentialStore(), client = HttpClient(MockEngine { respond(session) }), now = { 1 })
        account.authenticate("student_a", "Synthetic-password-123!", false)
        var requests=0
        val remote = HttpSyncRemote(account,HttpClient(MockEngine { request ->
            requests++
            val body=Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("ios",body["platform"]!!.jsonPrimitive.content)
            if(request.url.encodedPath.endsWith("snapshot")) respond("""{"archive":{"version":1,"conversations":[],"folders":[],"appearance":"SYSTEM"},"versions":{},"cursor":"0","drafts":{}}""")
            else {
                assertEquals("stable_mutation",body["mutationId"]!!.jsonPrimitive.content)
                respond("""{"entity":"settings","id":"settings","version":"1","cursor":"1"}""")
            }
        }),platform="ios")
        val identity=SyncIdentity(origin,"user_a")
        remote.snapshot(identity,"install_1")
        remote.mutate(identity,PendingMutation("install_1","stable_mutation","settings","settings","0",buildJsonObject {put("appearance","DARK")}))
        assertEquals(2,requests);remote.close();account.close()
    }
    @Test fun wrongAccountOrOriginNeverSendsPendingContents() = runTest {
        val account = AccountManager(origin, MemoryCredentialStore(), client = HttpClient(MockEngine { respond(session) }), now = { 1 })
        account.authenticate("student_a", "Synthetic-password-123!", false)
        val remote = HttpSyncRemote(account, HttpClient(MockEngine { error("Must not send") }))
        for (identity in listOf(SyncIdentity(origin,"user_b"), SyncIdentity("https://other.invalid","user_a"))) {
            assertEquals("account_changed", assertFailsWith<BridgeRequestException> { remote.snapshot(identity,"install_1") }.code)
        }
        remote.close(); account.close()
    }
    @Test fun conflictIsPreservedAndUnauthorizedClearsOnlyTheMatchingSession() = runTest {
        val account = AccountManager(origin, MemoryCredentialStore(), client = HttpClient(MockEngine { respond(session) }), now = { 1 })
        account.authenticate("student_a", "Synthetic-password-123!", false)
        var status = HttpStatusCode.Conflict
        val remote = HttpSyncRemote(account, HttpClient(MockEngine {
            respond(if(status == HttpStatusCode.Conflict) """{"error":{"code":"version_conflict","message":"private server detail"}}"""
                else """{"error":{"code":"unauthorized"}}""", status)
        }))
        val identity = SyncIdentity(origin,"user_a")
        val conflict = assertFailsWith<BridgeRequestException> { remote.snapshot(identity,"install_1") }
        assertEquals("version_conflict", conflict.code); assertFalse(conflict.message!!.contains("private server"))
        assertNotNull(account.state.value.user)
        status = HttpStatusCode.Unauthorized
        assertFailsWith<BridgeRequestException> { remote.snapshot(identity,"install_1") }
        assertNull(account.state.value.user)
        remote.close(); account.close()
    }
}
