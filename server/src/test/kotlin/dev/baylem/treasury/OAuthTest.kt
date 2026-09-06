package dev.baylem.treasury

import dev.baylem.treasury.repository.treasuryJson
import dev.baylem.treasury.server.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class OAuthTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC)
    private val config = ServerConfig("jdbc:postgresql://localhost/test", "test", "test", "http://localhost", development = true,
        oauthProviders = mapOf("google" to OAuthProviderConfig("google", "test-client", "test-secret", "http://localhost/v1/auth/oauth/google/callback")))
    private val fakeProvider = object : OAuthIdentityClient {
        override suspend fun subject(provider: OAuthProviderConfig, code: String, verifier: String): String {
            assertEquals("google", provider.name)
            assertEquals("provider-code", code)
            assertEquals(43, verifier.length)
            return "provider-user-123"
        }
        override fun close() = Unit
    }
    @Test fun browserStateIsCookieBoundSingleUseAndSessionNeverEntersUrl() = testApplication {
        application { module(config, testStore(), FastPasswords(), clock, oauthClient = fakeProvider) }
        val browser = createClient { followRedirects = false }
        val start = browser.get("/v1/auth/oauth/google/start")
        assertEquals(HttpStatusCode.Found, start.status)
        val location = Url(start.headers[HttpHeaders.Location]!!)
        assertEquals("openid", location.parameters["scope"])
        assertEquals("S256", location.parameters["code_challenge_method"])
        val state = location.parameters["state"]!!
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/v1/auth/oauth/google/callback?state=$state&code=provider-code").status)
        val callback = browser.get("/v1/auth/oauth/google/callback?state=$state&code=provider-code") { header(HttpHeaders.Cookie, "treasury_oauth_state=$state") }
        assertEquals(HttpStatusCode.OK, callback.status)
        val cookies = callback.headers.getAll(HttpHeaders.SetCookie)!!.joinToString(";")
        assertTrue("HttpOnly" in cookies)
        assertTrue("SameSite=Lax" in cookies)
        assertFalse("token" in callback.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/v1/auth/oauth/google/callback?state=$state&code=provider-code") { header(HttpHeaders.Cookie, "treasury_oauth_state=$state") }.status)
    }

    @Test fun nativeHandoffRequiresSecretBrowserBindingAndCodeFromTheApp() = testApplication {
        application { module(config, testStore(), FastPasswords(), clock, oauthClient = fakeProvider) }
        val browser = createClient { followRedirects = false }
        val prepared = browser.post("/v1/auth/oauth/device") { contentType(ContentType.Application.Json); setBody("""{"provider":"google"}""") }
        assertEquals(HttpStatusCode.Created, prepared.status, prepared.bodyAsText())
        val device = treasuryJson.decodeFromString<OAuthDeviceResponse>(prepared.bodyAsText())
        suspend fun poll(secret: String = device.pollSecret) = browser.post("/v1/auth/oauth/device/poll") {
            contentType(ContentType.Application.Json); setBody(treasuryJson.encodeToString(OAuthDevicePollRequest(device.attemptId, secret)))
        }
        assertEquals(HttpStatusCode.Accepted, poll().status)
        assertEquals(HttpStatusCode.Gone, poll(Secrets.token()).status)
        val start = browser.get(Url(device.authorizationUrl).fullPath)
        val state = Url(start.headers[HttpHeaders.Location]!!).parameters["state"]!!
        val callback = browser.get("/v1/auth/oauth/google/callback?state=$state&code=provider-code") { header(HttpHeaders.Cookie, "treasury_oauth_state=$state") }
        assertEquals(HttpStatusCode.OK, callback.status, callback.bodyAsText())
        assertFalse(device.verificationCode in callback.bodyAsText(), "Browser must not reveal the app's confirmation code")
        val approval = Regex("name=\"approval\" value=\"([^\"]+)\"").find(callback.bodyAsText())!!.groupValues[1]
        assertEquals(HttpStatusCode.Accepted, poll().status)
        val approve = browser.post("/v1/auth/oauth/device/approve") {
            contentType(ContentType.Application.FormUrlEncoded)
            header(HttpHeaders.Origin, "http://localhost")
            header(HttpHeaders.Cookie, "treasury_oauth_approval=$approval")
            setBody(listOf("attemptId" to device.attemptId, "approval" to approval, "verificationCode" to device.verificationCode).formUrlEncode())
        }
        assertEquals(HttpStatusCode.OK, approve.status, approve.bodyAsText())
        val completed = poll()
        assertEquals(HttpStatusCode.OK, completed.status, completed.bodyAsText())
        val session = treasuryJson.decodeFromString<SessionResponse>(completed.bodyAsText())
        assertEquals(HttpStatusCode.OK, browser.get("/v1/auth/me") { bearerAuth(session.token) }.status)
        assertEquals(HttpStatusCode.Gone, poll().status)
    }
}
