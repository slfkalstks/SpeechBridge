package kc.ac.uc.speechbridge

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class Connection(val url: String = "http://localhost:8787", val token: String = "")
@Serializable data class Health(val status: String, val configured: Map<String, Boolean>,
    val available: Map<String, Boolean> = emptyMap(), val runtime: String? = null)
@Serializable data class TranslationRequest(val text: String, val from: String, val to: String, val revision: Int)
@Serializable data class TranslationResponse(val text: String, val revision: Int)
@Serializable data class SourceText(val id: String, val revision: Int, val text: String)
@Serializable data class ExtractionRequest(val utterances: List<SourceText>, val language: String = "ko")
@Serializable data class ExtractedItem(
    val sourceUtteranceId: String, val sourceRevision: Int, val kind: String,
    val text: String, val owner: String?, val dueText: String?,
    val evidenceQuote: String? = null,
)
@Serializable data class ExtractionResponse(val items: List<ExtractedItem>)
@Serializable data class OrganizationRequest(val utterances: List<SourceText>, val language: String, val folders: List<WorkFolder>)
@Serializable data class OrganizationResponse(val title: String, val category: Category, val workType: WorkType?, val folderId: String?, val summary: String)
@Serializable data class SpeechToken(val token: String, val region: String, val expiresInSeconds: Int)
@Serializable private data class ErrorBody(val code: String)
@Serializable private data class ErrorResponse(val error: ErrorBody)
class BridgeRequestException(val code: String, message: String) : Exception(message)

fun serviceErrorMessage(code: String, status: Int): String = when (code) {
    "runtime_offline" -> "AI 서버에 연결하지 못했습니다. 서버가 켜져 있는지 확인해 주세요."
    "model_missing", "local_model_required", "free_provider_pending" -> "서버의 AI 모델 준비가 필요합니다."
    "context_too_large", "payload_too_large" -> "처리할 내용이 너무 깁니다. 대화를 나누어 요청해 주세요."
    "incomplete_output", "invalid_model_output", "upstream_failed" -> "AI 결과를 확인하지 못했습니다. 원문을 확인한 뒤 다시 시도해 주세요."
    "model_timeout" -> "AI 응답 대기 시간이 지났습니다. 다시 시도해 주세요."
    "speech_pending" -> "음성 기능은 아직 연결 전입니다."
    else -> when (status) {
        400 -> "입력 형식을 확인해 주세요."
        401 -> "서버 접속 토큰이 일치하지 않습니다."
        429 -> "서버가 처리 중입니다. 잠시 후 다시 시도해 주세요."
        503 -> "서버 준비 상태를 확인해 주세요."
        504 -> "응답 시간이 초과되었습니다. 다시 시도해 주세요."
        in 300..399 -> "서버 주소가 변경되었습니다. 연결 설정을 확인해 주세요."
        else -> "요청에 실패했습니다. 연결 상태를 확인해 주세요."
    }
}

interface TextTranslator {
    suspend fun translate(connection: Connection, request: TranslationRequest): TranslationResponse
}
interface ActionExtractor {
    suspend fun extract(connection: Connection, request: ExtractionRequest): ExtractionResponse
}
interface BridgeApi : TextTranslator, ActionExtractor {
    suspend fun organize(connection: Connection, request: OrganizationRequest): OrganizationResponse =
        throw BridgeRequestException("organization_pending", "서버를 업데이트한 뒤 다시 정리해 주세요.")
    suspend fun health(connection: Connection): Health
    fun close() {}
}

fun validatedBaseUrl(raw: String): String {
    val url = Url(raw.trim())
    require(url.protocol == URLProtocol.HTTPS ||
        (url.protocol == URLProtocol.HTTP && url.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) {
        "서버 주소는 HTTPS를 사용해 주세요. HTTP는 로컬 개발 주소만 허용합니다."
    }
    require(url.user == null && url.password == null && url.parameters.isEmpty() && url.fragment.isEmpty()) {
        "주소에 계정, 쿼리 또는 #을 넣을 수 없습니다."
    }
    return raw.trim().trimEnd('/')
}

class HttpBridgeApi(private val developerMode: Boolean = false) : BridgeApi {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 100_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 100_000 }
    }
    private suspend inline fun <reified T> request(c: Connection, path: String, body: String? = null): T {
        if (!developerMode) throw BridgeRequestException("service_pending", "AI 서비스 연결을 준비 중입니다. 기록은 기기에 저장됩니다.")
        val response = client.request(validatedBaseUrl(c.url) + path) {
            method = if (body == null) HttpMethod.Get else HttpMethod.Post
            if (body != null) {
                if (c.token.isBlank()) throw BridgeRequestException("missing_token", "설정에서 서버 접속 토큰을 입력해 주세요.")
                bearerAuth(c.token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        if (response.status.value !in 200..299) {
            val code = runCatching { json.decodeFromString<ErrorResponse>(response.bodyAsText()).error.code }.getOrDefault("unknown")
            throw BridgeRequestException(code, serviceErrorMessage(code, response.status.value))
        }
        return json.decodeFromString(response.bodyAsText())
    }
    override suspend fun health(connection: Connection) = request<Health>(connection, "/health")
    override suspend fun translate(connection: Connection, request: TranslationRequest) =
        request<TranslationResponse>(connection, "/translate", json.encodeToString(request))
    override suspend fun extract(connection: Connection, request: ExtractionRequest) =
        request<ExtractionResponse>(connection, "/extract", json.encodeToString(request))
    override suspend fun organize(connection: Connection, request: OrganizationRequest) =
        request<OrganizationResponse>(connection, "/organize", json.encodeToString(request))
    suspend fun speechToken(connection: Connection) = request<SpeechToken>(connection, "/speech/token", "{}")
    override fun close() { client.close() }
}
