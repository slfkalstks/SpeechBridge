package kc.ac.uc.speechbridge

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Separate from the conversation archive. Platform implementations encrypt or use Keychain. */
interface CredentialStore {
    suspend fun read(): String?
    suspend fun write(value: String?)
}
class MemoryCredentialStore : CredentialStore {
    private var value: String? = null
    override suspend fun read() = value
    override suspend fun write(value: String?) { this.value = value }
}
@Serializable data class AccountUser(val id: String, val username: String, val role: String = "member", val capabilities: List<String> = emptyList())
@Serializable internal data class AccountSession(val token: String, val expiresAt: Long, val user: AccountUser)
@Serializable private data class StoredAccount(val origin: String, val session: AccountSession)
@Serializable private data class LoginInput(val username: String, val password: String)
@Serializable private data class MeResponse(val user: AccountUser)
data class AccountState(val user: AccountUser? = null, val busy: Boolean = false, val ready: Boolean = false, val error: String? = null)

class AccountManager(
    rawEndpoint: String,
    private val store: CredentialStore,
    developerMode: Boolean = false,
    private val client: HttpClient = platformHttpClient {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 5_000 }
    },
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    val endpoint = rawEndpoint.takeIf { it.isNotBlank() }?.let {
        val valid = validatedBaseUrl(it)
        require(developerMode || Url(valid).protocol == URLProtocol.HTTPS)
        require(Url(valid).encodedPath.trim('/').isEmpty())
        valid
    }.orEmpty()
    val available get() = endpoint.isNotEmpty()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(AccountState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var session: AccountSession? = null

    private suspend fun request(path: String, body: String? = null, token: String? = null): String {
        if (!available) throw BridgeRequestException("service_pending", "서비스 연결을 준비 중입니다. 잠시 후 다시 시도해 주세요.")
        val response = client.request(endpoint + path) {
            method = if (body == null) HttpMethod.Get else HttpMethod.Post
            token?.let { bearerAuth(it) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val code = runCatching { json.decodeFromString<ErrorResponse>(text).error.code }.getOrDefault("unknown")
            throw BridgeRequestException(code, serviceErrorMessage(code, response.status.value))
        }
        return text
    }
    private suspend fun action(block: suspend () -> Unit) = mutex.withLock {
        mutable.value = mutable.value.copy(busy = true, error = null)
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: BridgeRequestException) { mutable.value = mutable.value.copy(error = e.message) }
        catch (_: Exception) { mutable.value = mutable.value.copy(error = "계정 정보를 확인하지 못했습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.") }
        finally { mutable.value = mutable.value.copy(busy = false, ready = true) }
    }
    suspend fun restore() = action {
        if (session != null) return@action
        val raw = try { store.read() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw BridgeRequestException("secure_storage_failed", "보관된 로그인 정보를 읽지 못했습니다. 기기 잠금을 해제하고 앱을 다시 열어 주세요.") }
        val saved = raw?.let { json.decodeFromString<StoredAccount>(it) } ?: return@action
        if (saved.origin != endpoint || saved.session.expiresAt <= now()) {
            store.write(null)
            mutable.value = mutable.value.copy(error = "다시 로그인해 주세요.")
            return@action
        }
        session = saved.session
        mutable.value = mutable.value.copy(user = saved.session.user)
        try {
            val user = json.decodeFromString<MeResponse>(request("/auth/me", token = saved.session.token)).user
            mutable.value = mutable.value.copy(user = user)
        } catch (e: BridgeRequestException) {
            if (e.code == "unauthorized") clearSession()
            throw e
        }
        // Offline restore retains encrypted credentials for a later connection attempt.
    }
    suspend fun authenticate(username: String, password: String, register: Boolean) = action {
        val clean = username.trim().lowercase()
        if (!Regex("[a-z0-9_]{3,32}").matches(clean) || password.length !in 12..128) {
            throw BridgeRequestException("invalid_credentials", serviceErrorMessage("invalid_credentials", 400))
        }
        val next = json.decodeFromString<AccountSession>(request(if(register) "/auth/register" else "/auth/login", json.encodeToString(LoginInput(clean, password))))
        try { store.write(json.encodeToString(StoredAccount(endpoint, next))) }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            runCatching { request("/auth/logout", "{}", next.token) }
            throw BridgeRequestException("secure_storage_failed", "로그인을 안전하게 저장하지 못했습니다. 다시 시도해 주세요.")
        }
        session = next
        mutable.value = mutable.value.copy(user = next.user)
    }
    private suspend fun clearSession() {
        session = null
        mutable.value = mutable.value.copy(user = null)
        store.write(null)
    }
    suspend fun logout() = action {
        // Server revocation must succeed before reporting logout. Offline failures remain retryable.
        session?.let {
            try { request("/auth/logout", "{}", it.token) }
            catch (e: BridgeRequestException) { if (e.code != "unauthorized") throw e }
        }
        clearSession()
    }
    suspend fun connection(): Connection = mutex.withLock {
        connectionUnlocked()
    }
    suspend fun connectionFor(identity: SyncIdentity): Connection = mutex.withLock {
        if (identity.origin != endpoint || session?.user?.id != identity.userId) {
            throw BridgeRequestException("account_changed", "계정이 변경되었습니다. 해당 계정으로 다시 로그인해 주세요.")
        }
        connectionUnlocked()
    }
    private suspend fun connectionUnlocked(): Connection {
        if (!available) throw BridgeRequestException("service_pending", "서비스 연결을 준비 중입니다. 잠시 후 다시 시도해 주세요.")
        val current = session
        if (current == null || current.expiresAt <= now()) {
            if (current != null) clearSession()
            throw BridgeRequestException("login_required", "다시 로그인해 주세요. 전송 대기 내용은 보관됩니다.")
        }
        return Connection(endpoint, current.token)
    }
    suspend fun expired(token: String) = mutex.withLock {
        if (session?.token == token) {
            clearSession()
            mutable.value = mutable.value.copy(error = "로그인이 만료되었습니다. 다시 로그인해 주세요.")
        }
    }
    fun close() { client.close() }
}
