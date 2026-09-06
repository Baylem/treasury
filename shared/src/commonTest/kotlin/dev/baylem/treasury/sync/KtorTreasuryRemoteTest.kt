package dev.baylem.treasury.sync

import dev.baylem.treasury.repository.treasuryJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorTreasuryRemoteTest {
    @Test fun authAndSyncUseTheDocumentedRoutesAndBearerHeader() = runTest {
        var count = 0
        val engine = MockEngine { request ->
            count++
            val body = if (request.url.encodedPath == "/v1/auth/login") {
                assertNull(request.headers[HttpHeaders.Authorization])
                """{"token":"private-token","ownerId":"user","expiresAt":"2027-01-01T00:00:00Z"}"""
            } else {
                assertEquals("/v1/sync", request.url.encodedPath)
                assertEquals("Bearer private-token", request.headers[HttpHeaders.Authorization])
                assertEquals("2026-01-01T00:00:00Z", request.url.parameters["cursor"])
                """{"snapshot":{},"cursor":"2026-01-02T00:00:00Z","hasMore":false}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(treasuryJson) } }
        val remote = KtorTreasuryRemote("https://treasury.example", client)
        try {
            val session = remote.login("user@example.com", "long-test-password")
            val page = remote.pull(session, "2026-01-01T00:00:00Z")
            assertEquals("2026-01-02T00:00:00Z", page.cursor)
            assertEquals(2, count)
        } finally { remote.close() }
    }

    @Test fun apiErrorsBecomeSafeActionableExceptions() = runTest {
        val engine = MockEngine {
            respond("""{"code":"rate_limited","message":"Wait before trying again.","requestId":"request"}""",
                HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(treasuryJson) } }
        val remote = KtorTreasuryRemote("http://localhost:8080", client)
        try {
            val failure = assertFailsWith<RemoteException> { remote.login("user@example.com", "password") }
            assertEquals(429, failure.statusCode)
            assertEquals("rate_limited", failure.code)
            assertEquals("Wait before trying again.", failure.message)
        } finally { remote.close() }
    }

    @Test fun insecureRemoteAndCredentialBearingUrlsAreRejected() {
        val client = HttpClient(MockEngine { error("No request should be made") })
        try {
            assertFailsWith<IllegalArgumentException> { KtorTreasuryRemote("http://example.com", client) }
            assertFailsWith<IllegalArgumentException> { KtorTreasuryRemote("https://user:password@example.com", client) }
            assertFailsWith<IllegalArgumentException> { KtorTreasuryRemote("https://example.com?token=secret", client) }
        } finally { client.close() }
    }

    @Test fun serverCanonicalizationNormalizesEquivalentOriginsAndPreservesPaths() {
        assertEquals("https://example.com/api", canonicalServerUrl(" https://EXAMPLE.COM:443/api/ "))
        assertEquals("https://example.com", canonicalServerUrl("https://example.com/"))
        assertEquals("http://localhost:8080", canonicalServerUrl("http://LOCALHOST:8080/"))
        assertEquals("https://example.com:8443/api", canonicalServerUrl("https://example.com:8443/api/"))
    }

    @Test fun oversizedServerResponsesAreRejectedBeforeDecoding() = runTest {
        val engine = MockEngine {
            respond("{}", HttpStatusCode.OK, headersOf(
                HttpHeaders.ContentType to listOf("application/json"),
                HttpHeaders.ContentLength to listOf("999999999"),
            ))
        }
        val remote = KtorTreasuryRemote("https://treasury.example", HttpClient(engine))
        try {
            val failure = assertFailsWith<IllegalArgumentException> { remote.login("user@example.com", "password") }
            assertTrue(failure.message.orEmpty().contains("size limit"))
        } finally { remote.close() }
    }

    @Test fun streamedErrorBodiesAreBoundedEvenWithoutContentLength() = runTest {
        val engine = MockEngine {
            respond("x".repeat(65_537), HttpStatusCode.InternalServerError,
                headersOf(HttpHeaders.ContentType, "text/html"))
        }
        val remote = KtorTreasuryRemote("https://treasury.example", HttpClient(engine))
        try {
            val failure = assertFailsWith<IllegalStateException> { remote.login("user@example.com", "password") }
            assertTrue(failure.message.orEmpty().contains("size limit"))
        } finally { remote.close() }
    }

    @Test fun oauthDevicePollingDistinguishesPendingFromACompletedSession() = runTest {
        var pollCount = 0
        val engine = MockEngine { request ->
            val (status, body) = when (request.url.encodedPath) {
                "/v1/auth/providers" -> HttpStatusCode.OK to """{"providers":["google","github","discord"]}"""
                "/v1/auth/oauth/device" -> HttpStatusCode.Created to """{"attemptId":"attempt","pollSecret":"private-poll-token","authorizationUrl":"https://treasury.example/device","verificationCode":"12345678","expiresAt":"2099-01-01T00:00:00Z"}"""
                "/v1/auth/oauth/device/poll" -> if (++pollCount == 1) HttpStatusCode.Accepted to """{"status":"pending"}"""
                    else HttpStatusCode.OK to """{"token":"token","ownerId":"owner","expiresAt":"2099-01-01T00:00:00Z"}"""
                else -> error("Unexpected route")
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val remote = KtorTreasuryRemote("https://treasury.example", HttpClient(engine))
        try {
            assertEquals(listOf("google", "github", "discord"), remote.providers())
            val attempt = remote.beginOAuthDevice("google")
            assertEquals("12345678", attempt.verificationCode)
            assertNull(remote.pollOAuthDevice(attempt.attemptId, attempt.pollSecret))
            assertEquals("owner", remote.pollOAuthDevice(attempt.attemptId, attempt.pollSecret)?.ownerId)
            assertTrue(!attempt.toString().contains(attempt.pollSecret))
        } finally { remote.close() }
    }

    @Test fun passwordRecoveryAndEmailVerificationUseBoundedJsonRequests() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath == "/v1/auth/email-status") {
                assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
                respond("""{"deliveryAvailable":true,"verified":false}""", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            } else respond("", HttpStatusCode.NoContent)
        }
        val remote = KtorTreasuryRemote("https://treasury.example", HttpClient(engine))
        val session = RemoteSession("token", "owner", "2099-01-01T00:00:00Z")
        try {
            remote.requestPasswordReset("user@example.com")
            remote.completePasswordReset("reset-token", "strong-new-password")
            assertEquals(EmailVerificationStatus(true, false), remote.emailStatus(session))
            remote.requestEmailVerification(session)
            remote.completeEmailVerification("verify-token")
            assertEquals(listOf("/v1/auth/password-reset/request", "/v1/auth/password-reset/complete", "/v1/auth/email-status",
                "/v1/auth/email-verification/request", "/v1/auth/email-verification/complete"), paths)
        } finally { remote.close() }
    }
}
