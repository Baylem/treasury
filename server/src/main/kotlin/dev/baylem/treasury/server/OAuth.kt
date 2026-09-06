package dev.baylem.treasury.server

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

interface OAuthIdentityClient : AutoCloseable {
    suspend fun subject(provider: OAuthProviderConfig, code: String, verifier: String): String
}

class ProviderIdentityClient : OAuthIdentityClient {
    private val http = HttpClient(CIO) {
        expectSuccess = true
        followRedirects = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 10_000 }
    }
    override suspend fun subject(provider: OAuthProviderConfig, code: String, verifier: String): String {
        val tokenUrl = when (provider.name) {
            "google" -> "https://oauth2.googleapis.com/token"
            "github" -> "https://github.com/login/oauth/access_token"
            "discord" -> "https://discord.com/api/oauth2/token"
            else -> invalid("Unsupported OAuth provider")
        }
        val tokenResponse: JsonObject = http.submitForm(tokenUrl, Parameters.build {
            append("client_id", provider.clientId)
            append("client_secret", provider.clientSecret)
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", provider.callbackUrl)
            if (provider.name != "discord") append("code_verifier", verifier)
        }) { accept(ContentType.Application.Json) }.body()
        val token = tokenResponse["access_token"]?.jsonPrimitive?.contentOrNull ?: unauthorized()
        val userUrl = when (provider.name) {
            "google" -> "https://openidconnect.googleapis.com/v1/userinfo"
            "github" -> "https://api.github.com/user"
            "discord" -> "https://discord.com/api/users/@me"
            else -> invalid("Unsupported OAuth provider")
        }
        val user: JsonObject = http.get(userUrl) {
            bearerAuth(token)
            accept(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, "Treasury/1.0")
        }.body()
        return user[if (provider.name == "google") "sub" else "id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 1..255 } ?: unauthorized()
    }
    override fun close() { http.close() }
}

class OAuthService(
    private val config: ServerConfig,
    private val store: ServerStore,
    private val auth: AuthService,
    private val client: OAuthIdentityClient,
    private val clock: Clock,
) {
    val sessionCookie = if (config.development) "treasury_session" else "__Host-treasury_session"
    private val stateCookie = if (config.development) "treasury_oauth_state" else "__Host-treasury_oauth_state"
    private val approvalCookie = if (config.development) "treasury_oauth_approval" else "__Host-treasury_oauth_approval"

    private fun ApplicationCall.cookie(name: String, value: String, maxAge: Int) {
        response.cookies.append(Cookie(name, value, encoding = CookieEncoding.RAW, maxAge = maxAge, path = "/", secure = !config.development, httpOnly = true, extensions = mapOf("SameSite" to "Lax")))
    }
    fun clearSession(call: ApplicationCall) { call.cookie(sessionCookie, "", 0) }

    suspend fun start(call: ApplicationCall, name: String, deviceAttemptId: String? = null) {
        val provider = config.oauthProviders[name] ?: throw ApiException(HttpStatusCode.NotFound, "provider_unavailable", "This sign-in provider is not configured")
        val state = Secrets.token()
        val verifier = Secrets.token()
        store.addOAuthFlow(OAuthFlow(Secrets.hash(state), name, verifier, clock.millis() + 600_000, deviceAttemptId))
        call.cookie(stateCookie, state, 600)
        val endpoint = when (name) {
            "google" -> "https://accounts.google.com/o/oauth2/v2/auth"
            "github" -> "https://github.com/login/oauth/authorize"
            "discord" -> "https://discord.com/oauth2/authorize"
            else -> invalid("Unsupported OAuth provider")
        }
        val url = URLBuilder(endpoint).apply {
            parameters.append("client_id", provider.clientId)
            parameters.append("redirect_uri", provider.callbackUrl)
            parameters.append("response_type", "code")
            parameters.append("state", state)
            parameters.append("scope", when (name) { "google" -> "openid"; "github" -> ""; "discord" -> "identify"; else -> "" })
            // Discord's documented confidential client flow uses client authentication + state.
            if (name != "discord") {
                parameters.append("code_challenge", Secrets.challenge(verifier))
                parameters.append("code_challenge_method", "S256")
            }
        }.buildString()
        call.respondRedirect(url)
    }

    suspend fun callback(call: ApplicationCall, name: String) {
        val provider = config.oauthProviders[name] ?: unauthorized()
        val state = call.request.queryParameters["state"]?.takeIf { it.length == 43 } ?: unauthorized()
        val cookie = call.request.cookies[stateCookie] ?: unauthorized()
        if (!MessageDigest.isEqual(state.toByteArray(), cookie.toByteArray())) unauthorized()
        call.cookie(stateCookie, "", 0)
        val flow = store.consumeOAuthFlow(Secrets.hash(state), name, clock.millis()) ?: unauthorized()
        val code = call.request.queryParameters["code"]?.takeIf { it.length in 1..2048 } ?: unauthorized()
        val subject = try { client.subject(provider, code, flow.verifier) } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (_: Exception) {
            throw ApiException(HttpStatusCode.BadGateway, "provider_error", "The sign-in provider could not complete this request")
        }
        val user = store.oauthUser(name, subject, clock.millis())
        if (flow.deviceAttemptId != null) {
            val approval = Secrets.token()
            if (!store.stageDeviceAttempt(flow.deviceAttemptId, user.id, Secrets.hash(approval), clock.millis())) unauthorized()
            call.cookie(approvalCookie, approval, 600)
            call.respondText("""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Approve Treasury sign-in</title><main><h1>Connect your Treasury app</h1><p>Enter the 8-digit code displayed in your Treasury app. Only continue if you started this sign-in yourself.</p><form action="/v1/auth/oauth/device/approve" method="post"><input type="hidden" name="attemptId" value="${flow.deviceAttemptId}"><input type="hidden" name="approval" value="$approval"><label>Code from your Treasury app <input name="verificationCode" inputmode="numeric" pattern="[0-9]{8}" minlength="8" maxlength="8" required autocomplete="one-time-code"></label><button type="submit">Approve sign-in</button></form><p>Never enter a code sent to you by another person.</p></main></html>""", ContentType.Text.Html)
            return
        }
        val session = auth.createSession(user.id)
        call.cookie(sessionCookie, session.token, 30 * 24 * 60 * 60)
        call.respondText("You are signed in to Treasury. You may return to the application.", ContentType.Text.Plain)
    }

    suspend fun createDevice(name: String): OAuthDeviceResponse {
        if (name !in config.oauthProviders) throw ApiException(HttpStatusCode.NotFound, "provider_unavailable", "This sign-in provider is not configured")
        val id = UUID.randomUUID().toString()
        val secret = Secrets.token()
        val code = Secrets.verificationCode()
        val expiresAt = clock.millis() + 600_000
        store.createDeviceAttempt(OAuthDevice(id, Secrets.hash(secret), Secrets.hash(code), name, expiresAt), clock.millis())
        return OAuthDeviceResponse(id, secret, "${config.publicUrl}/v1/auth/oauth/device/start?attemptId=$id", code, cursorString(expiresAt))
    }
    suspend fun startDevice(call: ApplicationCall) {
        val id = call.request.queryParameters["attemptId"]?.takeIf(::isUuid) ?: unauthorized()
        val attempt = store.deviceAttempt(id, clock.millis()) ?: unauthorized()
        start(call, attempt.provider, id)
    }
    suspend fun approveDevice(call: ApplicationCall) {
        if (call.request.headers[HttpHeaders.Origin] != config.publicUrl) unauthorized()
        val body = call.receiveParameters()
        val approval = body["approval"]?.takeIf { it.length == 43 } ?: unauthorized()
        val cookie = call.request.cookies[approvalCookie] ?: unauthorized()
        if (!MessageDigest.isEqual(approval.toByteArray(), cookie.toByteArray())) unauthorized()
        val id = body["attemptId"]?.takeIf(::isUuid) ?: unauthorized()
        val code = body["verificationCode"]?.takeIf { it.length == 8 && it.all(Char::isDigit) } ?: invalid("Enter the 8-digit code from your Treasury app")
        if (!store.approveDeviceAttempt(id, Secrets.hash(approval), Secrets.hash(code), clock.millis())) invalid("The code is incorrect or this sign-in has expired; restart sign-in if needed")
        call.cookie(approvalCookie, "", 0)
        call.respondText("Your Treasury app is now connected. You can close this tab.", ContentType.Text.Plain)
    }
    suspend fun pollDevice(call: ApplicationCall, request: OAuthDevicePollRequest) {
        if (!isUuid(request.attemptId) || request.pollSecret.length != 43) unauthorized()
        when (val result = store.consumeDeviceAttempt(request.attemptId, Secrets.hash(request.pollSecret), clock.millis())) {
            DevicePoll.Pending -> call.respond(HttpStatusCode.Accepted, StatusResponse("pending"))
            DevicePoll.Expired -> throw ApiException(HttpStatusCode.Gone, "sign_in_expired", "This sign-in has expired or was already completed")
            is DevicePoll.Approved -> call.respond(auth.createSession(result.ownerId))
        }
    }
}

@Serializable data class OAuthDeviceRequest(val provider: String)
@Serializable data class OAuthDevicePollRequest(val attemptId: String, val pollSecret: String)
@Serializable data class OAuthDeviceResponse(val attemptId: String, val pollSecret: String, val authorizationUrl: String, val verificationCode: String, val expiresAt: String)
