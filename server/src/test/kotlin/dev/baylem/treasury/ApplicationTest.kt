package dev.baylem.treasury

import dev.baylem.treasury.domain.*
import dev.baylem.treasury.repository.treasuryJson
import dev.baylem.treasury.server.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class ApplicationTest {
    private val now = Instant.parse("2026-09-06T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val config = ServerConfig("jdbc:postgresql://localhost/test", "test", "test", "http://localhost", development = true)
    private fun ApplicationTestBuilder.setup() {
        val store = testStore()
        application { module(config, store, FastPasswords(), clock) }
    }
    private suspend fun HttpClient.register(email: String = "person@example.com"): SessionResponse {
        val response = post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(treasuryJson.encodeToString(CredentialsRequest(email, "a very strong password")))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return treasuryJson.decodeFromString(response.bodyAsText())
    }
    private fun account(owner: String) = Account(
        EntityMeta(UUID.randomUUID().toString(), owner, kotlin.time.Instant.parse(now.toString()), kotlin.time.Instant.parse(now.toString())),
        "Checking", balanceDate = LocalDate(2026, 9, 1), openingBalanceMinor = 100_000,
    )

    @Test fun healthAndUnknownRoutesExposeNoInternals() = testApplication {
        setup()
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
        val response = client.get("/missing")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertNotNull(response.headers["X-Request-ID"])
    }

    @Test fun registerLoginLogoutAndExpiryAreEnforced() = testApplication {
        setup()
        val session = client.register("PERSON@example.com")
        assertEquals(43, session.token.length)
        val me = client.get("/v1/auth/me") { bearerAuth(session.token) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("person@example.com", treasuryJson.decodeFromString<UserResponse>(me.bodyAsText()).email)
        val wrongPassword = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(treasuryJson.encodeToString(CredentialsRequest("person@example.com", "wrong")))
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
        val login = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(treasuryJson.encodeToString(CredentialsRequest("person@example.com", "a very strong password")))
        }
        assertEquals(HttpStatusCode.OK, login.status)
        assertEquals(HttpStatusCode.NoContent, client.post("/v1/auth/logout-all") { bearerAuth(session.token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/auth/me") { bearerAuth(session.token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/sync").status)
    }

    @Test fun ownerInjectionIsRejectedAndOtherAccountsCannotReadData() = testApplication {
        setup()
        val alice = client.register("alice@example.com")
        val bob = client.register("bob@example.com")
        val data = TreasurySnapshot(accounts = listOf(account(alice.ownerId)))
        val rejected = client.post("/v1/sync") {
            bearerAuth(bob.token); contentType(ContentType.Application.Json); setBody(treasuryJson.encodeToString(data))
        }
        assertEquals(HttpStatusCode.Forbidden, rejected.status)
        assertEquals(HttpStatusCode.OK, client.post("/v1/sync") {
            bearerAuth(alice.token); contentType(ContentType.Application.Json); setBody(treasuryJson.encodeToString(data))
        }.status)
        val other = client.get("/v1/sync") { bearerAuth(bob.token) }
        assertTrue(treasuryJson.decodeFromString<SyncPage>(other.bodyAsText()).snapshot.accounts.isEmpty())
    }

    @Test fun malformedRequestsAndOversizedBodiesFailBeforeMutation() = testApplication {
        setup()
        val malformed = client.post("/v1/auth/register") { contentType(ContentType.Application.Json); setBody("{invalid}") }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        val oversized = client.post("/v1/auth/register") { contentType(ContentType.Application.Json); setBody(" ".repeat(9_000)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
        val weak = client.post("/v1/auth/register") { contentType(ContentType.Application.Json); setBody("""{"email":"a@example.com","password":"short"}""") }
        assertEquals(HttpStatusCode.BadRequest, weak.status)
        val session = client.register()
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/sync?cursor=garbage") { bearerAuth(session.token) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/sync?limit=501") { bearerAuth(session.token) }.status)
    }

    @Test fun erasureRequiresConfirmationThenRevokesAndPurges() = testApplication {
        setup()
        val session = client.register()
        val invalid = client.delete("/v1/account") {
            bearerAuth(session.token); contentType(ContentType.Application.Json); setBody("""{"confirmation":"no"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/auth/me") { bearerAuth(session.token) }.status)
        val erased = client.delete("/v1/account") {
            bearerAuth(session.token); contentType(ContentType.Application.Json); setBody("""{"confirmation":"DELETE_MY_ACCOUNT"}""")
        }
        assertEquals(HttpStatusCode.NoContent, erased.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/auth/me") { bearerAuth(session.token) }.status)
        val replacement = client.register()
        assertNotEquals(session.ownerId, replacement.ownerId)
    }

    @Test fun cookieWritesRequireCsrfHeaderAndTrustedOrigin() = testApplication {
        setup()
        val session = client.register()
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/auth/logout") { header(HttpHeaders.Cookie, "treasury_session=${session.token}") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/auth/logout") {
            header(HttpHeaders.Cookie, "treasury_session=${session.token}"); header("X-Treasury-CSRF", "1"); header(HttpHeaders.Origin, "https://evil.example")
        }.status)
        assertEquals(HttpStatusCode.NoContent, client.post("/v1/auth/logout") {
            header(HttpHeaders.Cookie, "treasury_session=${session.token}"); header("X-Treasury-CSRF", "1"); header(HttpHeaders.Origin, "http://localhost")
        }.status)
    }

    @Test fun authenticationRateLimitReturnsRetryAfter() = testApplication {
        setup()
        repeat(15) {
            client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"email":"person@example.com","password":"bad"}""") }
        }
        val limited = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("60", limited.headers[HttpHeaders.RetryAfter])
    }
}
