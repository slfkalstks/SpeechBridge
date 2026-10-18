package kc.ac.uc.speechbridge

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64

class HttpVoiceApi(private val account: AccountManager, private val client: HttpClient = platformHttpClient {
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 100_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 100_000 }
}) : VoiceApi {
    private suspend fun request(path: String, body: JsonObject): HttpResponse {
        val connection = account.connection()
        return withTimeout(95_000) {
            var attempts = 0
            while(true) {
                val response = client.post(validatedBaseUrl(connection.url) + path) {
                    bearerAuth(connection.token)
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
                if(response.status.value in 200..299) return@withTimeout response
                if(response.status.value == 401) account.expired(connection.token)
                val code = runCatching { ArchiveCodec.json.decodeFromString<ErrorResponse>(response.bodyAsText()).error.code }.getOrDefault("unknown")
                if(response.status.value == 429 && code == "busy" && attempts++ < 60) {
                    // Keep this recording and its original session together while text inference finishes.
                    // Cancellation exits the delay; a later login must never receive the old audio.
                    delay(1_000)
                } else throw BridgeRequestException(code, serviceErrorMessage(code, response.status.value))
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        }
    }
    override suspend fun transcribe(wav: ByteArray, language: String): Transcription {
        PcmWave.decode(wav, 16000)
        require(language == "auto" || Language.entries.any { it.code == language })
        return ArchiveCodec.json.decodeFromString(request("/speech/transcribe", buildJsonObject {
            put("audioBase64", Base64.encode(wav)); put("language", language)
        }).bodyAsText())
    }
    override suspend fun synthesize(text: String, language: Language): ByteArray {
        require(text.isNotBlank() && text.length <= 300)
        val response = request("/speech/synthesize", buildJsonObject { put("text",text); put("language",language.code) })
        require(response.contentType()?.withoutParameters() == ContentType("audio", "wav"))
        return response.bodyAsBytes().also { PcmWave.decode(it,24000) }
    }
    override fun close() = client.close()
}

internal class UnavailableVoiceApi : VoiceApi {
    override suspend fun transcribe(wav: ByteArray, language: String): Transcription = throw BridgeRequestException("speech_pending", "음성 서버에 연결할 수 없습니다.")
    override suspend fun synthesize(text: String, language: Language): ByteArray = throw BridgeRequestException("speech_pending", "음성 서버에 연결할 수 없습니다.")
}
