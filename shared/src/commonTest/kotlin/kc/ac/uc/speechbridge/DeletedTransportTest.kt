package kc.ac.uc.speechbridge
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlin.test.*
class DeletedTransportTest {
    @Test fun textVoiceAndSyncKeepDeletedIdentityForLocalCleanup()=runTest {withContext(Dispatchers.Default) {
        for(kind in listOf("text","voice","sync")) {
            val origin="https://deleted.example.invalid"
            val account=AccountManager(origin,MemoryCredentialStore(),client=HttpClient(MockEngine {respond("""{"token":"session","expiresAt":10000,"user":{"id":"a","username":"member_a"}}""")}),now={1})
            account.authenticate("member_a","Synthetic-password-123",false)
            val client=HttpClient(MockEngine {respond("""{"error":{"code":"account_deleted"}}""",HttpStatusCode.Unauthorized)})
            try {
                assertFailsWith<BridgeRequestException> {
                    when(kind) {
                        "text"->HttpBridgeApi(account=account,client=client).translate(Connection(origin,"session"),TranslationRequest("Hello","en","ko",1))
                        "voice"->HttpVoiceApi(account,client).synthesize("Hello",Language.EN)
                        else->HttpSyncRemote(account,client).snapshot(SyncIdentity(origin,"a"),"device")
                    }
                }
                assertEquals(AccountDeletion.CONFIRMED,account.state.value.deletion,kind)
                assertEquals("a",account.state.value.user?.id)
            }finally{client.close();account.close()}
        }
    }}
}
