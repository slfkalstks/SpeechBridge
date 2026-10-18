package kc.ac.uc.speechbridge

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*

class HttpSyncRemote(private val account: AccountManager, private val client: HttpClient = platformHttpClient {
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 15_000 }
}, private val platform: String = getPlatform().kind) : SyncRemote {
    init { require(platform in setOf("android","ios","test")) }
    private val json = ArchiveCodec.json
    private suspend inline fun <reified T> request(identity: SyncIdentity, path: String, body: String): T {
        val connection = account.connectionFor(identity)
        val response = client.post(connection.url + path) {
            bearerAuth(connection.token)
            contentType(ContentType.Application.Json)
            setBody(JsonObject(json.parseToJsonElement(body).jsonObject + ("platform" to JsonPrimitive(platform))).toString())
        }
        if(response.status.value !in 200..299) {
            if(response.status.value == 401) account.expired(connection.token)
            val code = runCatching { json.decodeFromString<ErrorResponse>(response.bodyAsText()).error.code }.getOrDefault("unknown")
            throw BridgeRequestException(code, serviceErrorMessage(code, response.status.value))
        }
        return json.decodeFromString(response.bodyAsText())
    }
    override suspend fun snapshot(identity: SyncIdentity, deviceId: String): DataSnapshot = request(identity,
        "/data/snapshot", buildJsonObject { put("deviceId",deviceId) }.toString())
    override suspend fun mutate(identity: SyncIdentity, mutation: PendingMutation): MutationReceipt =
        request(identity,"/data/mutate",json.encodeToString(mutation))
    override fun close() { client.close() }
}
