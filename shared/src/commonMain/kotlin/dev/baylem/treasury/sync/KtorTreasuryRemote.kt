package dev.baylem.treasury.sync

import dev.baylem.treasury.domain.TreasurySnapshot
import dev.baylem.treasury.repository.entities
import dev.baylem.treasury.repository.treasuryJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** HTTPS-only outside loopback, bounded requests, no redirects, and no credential logging. */
class KtorTreasuryRemote(baseUrl: String, client: HttpClient? = null) : TreasuryRemote {
    private val endpoint = canonicalServerUrl(baseUrl)
    private val client = client ?: HttpClient {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    override suspend fun register(email: String, password: String): RemoteSession =
        decode(request("/v1/auth/register", HttpMethod.Post, body = treasuryJson.encodeToString(CredentialsRequest(email.trim(), password))))

    override suspend fun login(email: String, password: String): RemoteSession =
        decode(request("/v1/auth/login", HttpMethod.Post, body = treasuryJson.encodeToString(CredentialsRequest(email.trim(), password))))

    override suspend fun me(session: RemoteSession): RemoteUser =
        decode(request("/v1/auth/me", HttpMethod.Get, session))

    override suspend fun logout(session: RemoteSession) {
        request("/v1/auth/logout", HttpMethod.Post, session)
    }

    override suspend fun logoutAll(session: RemoteSession) {
        request("/v1/auth/logout-all", HttpMethod.Post, session)
    }

    override suspend fun reauthenticate(session: RemoteSession, password: String): RemoteSession =
        decode(request("/v1/auth/reauthenticate", HttpMethod.Post, session, treasuryJson.encodeToString(PasswordRequest(password))))

    override suspend fun eraseAccount(session: RemoteSession, confirmation: String) {
        require(confirmation == "DELETE_MY_ACCOUNT") { "Type DELETE_MY_ACCOUNT to confirm permanent account erasure." }
        request("/v1/account", HttpMethod.Delete, session, treasuryJson.encodeToString(EraseRequest(confirmation)))
    }

    override suspend fun requestPasswordReset(email: String) {
        request("/v1/auth/password-reset/request", HttpMethod.Post, body = treasuryJson.encodeToString(EmailRequest(email.trim())))
    }

    override suspend fun completePasswordReset(token: String, password: String) {
        request("/v1/auth/password-reset/complete", HttpMethod.Post, body = treasuryJson.encodeToString(PasswordResetRequest(token.trim(), password)))
    }

    override suspend fun emailStatus(session: RemoteSession): EmailVerificationStatus =
        decode(request("/v1/auth/email-status", HttpMethod.Get, session))

    override suspend fun requestEmailVerification(session: RemoteSession) {
        request("/v1/auth/email-verification/request", HttpMethod.Post, session)
    }

    override suspend fun completeEmailVerification(token: String) {
        request("/v1/auth/email-verification/complete", HttpMethod.Post, body = treasuryJson.encodeToString(TokenRequest(token.trim())))
    }

    override suspend fun providers(): List<String> =
        decode<ProvidersResponse>(request("/v1/auth/providers", HttpMethod.Get)).providers

    override suspend fun beginOAuthDevice(provider: String): OAuthDeviceAttempt =
        decode(request("/v1/auth/oauth/device", HttpMethod.Post, body = treasuryJson.encodeToString(OAuthDeviceRequest(provider))))

    override suspend fun pollOAuthDevice(attemptId: String, pollSecret: String): RemoteSession? {
        val text = request("/v1/auth/oauth/device/poll", HttpMethod.Post,
            body = treasuryJson.encodeToString(OAuthPollRequest(attemptId, pollSecret)))
        val objectValue = treasuryJson.parseToJsonElement(text).jsonObject
        return if (objectValue["status"]?.jsonPrimitive?.content == "pending") null else decode<RemoteSession>(text)
    }

    override suspend fun pull(session: RemoteSession, cursor: String?, limit: Int): RemoteSyncPage {
        require(limit in 1..500)
        return decode(request("/v1/sync", HttpMethod.Get, session) {
            cursor?.let { parameter("cursor", it) }
            parameter("limit", limit)
        })
    }

    override suspend fun push(session: RemoteSession, snapshot: TreasurySnapshot): RemotePushResult {
        require(snapshot.entities().size <= 500) { "Sync batches may contain at most 500 records." }
        require(snapshot.entities().all { it.ownerId == session.ownerId }) { "This data belongs to a different owner." }
        return decode(request("/v1/sync", HttpMethod.Post, session, treasuryJson.encodeToString(snapshot)))
    }

    override suspend fun exportAccount(session: RemoteSession): TreasurySnapshot =
        decode(request("/v1/account/export", HttpMethod.Get, session))

    override fun close() = client.close()

    private suspend fun request(
        path: String,
        method: HttpMethod,
        session: RemoteSession? = null,
        body: String? = null,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): String = client.prepareRequest("$endpoint$path") {
        this.method = method
        session?.let { bearerAuth(it.token) }
        if (body != null) {
            require(body.encodeToByteArray().size < 1_048_576) { "The request exceeds the server's 1 MB limit." }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        configure()
    }.execute { response ->
        // execute(block) streams the response. Ordinary get/post would cache the entire body first.
        val text = boundedText(response, if (response.status.value in 200..299) 20 * 1_048_576 else 65_536)
        if (response.status.value !in 200..299) throwApiFailure(response, text)
        text
    }

    private inline fun <reified T> decode(text: String): T = try { treasuryJson.decodeFromString<T>(text) }
    catch (failure: Exception) { throw IllegalStateException("The server returned an invalid response.", failure) }

    private suspend fun boundedText(response: HttpResponse, limit: Int): String {
        val declaredSize = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        require(declaredSize == null || declaredSize <= limit) { "The server response exceeds the supported size limit." }
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8_192)
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        while (true) {
            val read = channel.readAvailable(buffer)
            if (read < 0) break
            check(read <= limit - total) { "The server response exceeds the supported size limit." }
            if (read > 0) {
                chunks += buffer.copyOf(read)
                total += read
            }
        }
        val bytes = ByteArray(total)
        var offset = 0
        chunks.forEach { chunk -> chunk.copyInto(bytes, offset); offset += chunk.size }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    private fun throwApiFailure(response: HttpResponse, text: String): Nothing {
        val error = try { treasuryJson.decodeFromString<RemoteError>(text) } catch (_: Exception) { null }
        val message = error?.message?.take(500) ?: when (response.status.value) {
            401 -> "Your session expired. Sign in again."
            403 -> "This account cannot access the requested data."
            413 -> "The sync request is too large."
            429 -> "Too many requests. Wait a minute before trying again."
            else -> "The server could not complete this request (${response.status.value})."
        }
        throw RemoteException(response.status.value, error?.code ?: "request_failed", message)
    }
}

/** Stable storage/network boundary: host case, default ports and trailing slashes are normalized. */
fun canonicalServerUrl(raw: String): String {
    val parsed = Url(raw.trim())
    val host = parsed.host.lowercase()
    require(parsed.protocol == URLProtocol.HTTPS || parsed.protocol == URLProtocol.HTTP &&
        host in setOf("localhost", "127.0.0.1", "[::1]", "::1")) {
        "Use an HTTPS server URL. HTTP is allowed only for a local development server."
    }
    require(host.isNotBlank() && parsed.user == null && parsed.password == null && parsed.parameters.isEmpty() && parsed.fragment.isEmpty()) {
        "The server URL cannot contain credentials, a query, or a fragment."
    }
    val authority = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
    val port = if (parsed.port == parsed.protocol.defaultPort) "" else ":${parsed.port}"
    return "${parsed.protocol.name}://$authority$port${parsed.encodedPath.trimEnd('/')}"
}
