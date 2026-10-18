package kc.ac.uc.speechbridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class HttpVoiceApiTest {
    private val origin = "https://voice.example.invalid"
    private suspend fun account(): AccountManager = AccountManager(origin, MemoryCredentialStore(),
        client = HttpClient(MockEngine { respond("""{"token":"synthetic-voice-token","expiresAt":10000,"user":{"id":"user_a","username":"student_a","role":"member"}}""") }), now = { 1 })
        .also { it.authenticate("student_a", "Synthetic-password-123!", false) }

    @Test fun automaticTranscriptionAndBinarySynthesisUseMemberToken() = runTest {
        val account = account()
        val input = PcmWave.encode(ShortArray(1600) { 1000 }, 16000)
        val output = PcmWave.encode(ShortArray(2400) { 1000 }, 24000)
        val api = HttpVoiceApi(account, HttpClient(MockEngine { request ->
            assertEquals("Bearer synthetic-voice-token", request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            if(request.url.encodedPath == "/speech/transcribe") {
                assertEquals("auto", body["language"]!!.jsonPrimitive.content)
                assertContentEquals(input, Base64.decode(body["audioBase64"]!!.jsonPrimitive.content))
                respond("""{"text":"こんにちは","language":"ja"}""")
            } else {
                assertEquals("/speech/synthesize", request.url.encodedPath)
                assertEquals("ko", body["language"]!!.jsonPrimitive.content)
                respond(output, headers = headersOf(HttpHeaders.ContentType, "audio/wav"))
            }
        }))
        assertEquals(Transcription("こんにちは", "ja"), api.transcribe(input))
        assertContentEquals(output, api.synthesize("안녕하세요", Language.KO))
        api.close(); account.close()
    }
    @Test fun unauthorizedAudioExpiresSessionAndNeverDisplaysPrivateServerErrors() = runTest {
        val account = account()
        val api = HttpVoiceApi(account, HttpClient(MockEngine {
            respond("""{"error":{"code":"unauthorized","message":"private detail"}}""", HttpStatusCode.Unauthorized)
        }))
        val error = assertFailsWith<BridgeRequestException> { api.synthesize("안녕", Language.KO) }
        assertFalse(error.message!!.contains("private detail")); assertNull(account.state.value.user)
        api.close(); account.close()
    }
    @Test fun invalidAudioAndOverlongSpeechAreRejectedBeforeNetwork() = runTest {
        val account = account()
        val api = HttpVoiceApi(account, HttpClient(MockEngine { error("Must not send") }))
        assertFails { api.transcribe(byteArrayOf(1,2)) }
        assertFails { api.synthesize("a".repeat(301), Language.EN) }
        api.close(); account.close()
    }
}
