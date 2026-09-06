package dev.baylem.treasury

import dev.baylem.treasury.domain.TreasurySnapshot
import dev.baylem.treasury.repository.treasuryJson
import dev.baylem.treasury.server.*
import dev.baylem.treasury.server.ServerConfig
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.bodylimit.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.net.URI
import java.time.Clock
import java.util.UUID

private val RequestId = AttributeKey<String>("treasury.requestId")

fun main() {
    val config = ServerConfig.fromEnvironment()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(config) }.start(wait = true)
}

fun Application.module(
    config: ServerConfig = ServerConfig.fromEnvironment(),
    store: ServerStore = PostgresStore.open(config),
    passwords: PasswordHasher = Argon2Passwords(),
    clock: Clock = Clock.systemUTC(),
    limiter: RequestLimiter = RequestLimiter(clock),
    oauthClient: OAuthIdentityClient = ProviderIdentityClient(),
    mailSender: MailSender? = config.mail?.let(::SmtpMailSender),
) {
    val auth = AuthService(store, passwords, clock)
    val trustedProxies = config.trustedProxyAddresses.mapNotNull(::parseIpAddress).toSet()
    fun ApplicationCall.clientAddress() = clientAddress(request.local.remoteHost, request.headers[HttpHeaders.XForwardedFor], trustedProxies)
    val oauth = OAuthService(config, store, auth, oauthClient, clock)
    val recovery = RecoveryService(store, passwords, clock)
    val maintenance = launch {
        while (isActive) {
            try {
                store.pruneExpired(clock.millis())
                mailSender?.let { sender -> store.claimMail(clock.millis()).forEach { mail ->
                    try { sender.send(mail); store.acknowledgeMail(mail.tokenHash) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { this@module.log.warn("Email delivery failed; queued for retry") }
                } }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { this@module.log.warn("Email outbox processing failed; will retry") }
            delay(10_000)
        }
    }
    monitor.subscribe(ApplicationStopping) { maintenance.cancel() }
    monitor.subscribe(ApplicationStopped) { store.close(); oauthClient.close() }
    install(ContentNegotiation) { json(treasuryJson) }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            if (cause.status == HttpStatusCode.TooManyRequests) call.response.header(HttpHeaders.RetryAfter, (cause.retryAfterSeconds ?: 60).toString())
            call.respond(cause.status, ErrorResponse(cause.code, cause.message, call.attributes.getOrNull(RequestId).orEmpty()))
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            val status = when (cause) {
                is PayloadTooLargeException -> HttpStatusCode.PayloadTooLarge
                is BadRequestException, is SerializationException, is IllegalArgumentException -> HttpStatusCode.BadRequest
                is UnsupportedMediaTypeException -> HttpStatusCode.UnsupportedMediaType
                else -> HttpStatusCode.InternalServerError
            }
            val requestId = call.attributes.getOrNull(RequestId).orEmpty()
            if (status == HttpStatusCode.InternalServerError) this@module.log.error("Request {} failed ({})", requestId, cause.javaClass.simpleName)
            call.respond(status, ErrorResponse(if (status == HttpStatusCode.InternalServerError) "internal_error" else "invalid_request", when (status) {
                HttpStatusCode.PayloadTooLarge -> "Request body exceeds the allowed size"
                HttpStatusCode.BadRequest -> "Request contains invalid data"
                HttpStatusCode.UnsupportedMediaType -> "Use application/json"
                else -> "The request could not be completed"
            }, requestId))
        }
        status(HttpStatusCode.NotFound) { call, status -> call.respond(status, ErrorResponse("not_found", "Endpoint not found", call.attributes.getOrNull(RequestId).orEmpty())) }
    }
    install(createApplicationPlugin("TreasurySecurity") {
        onCall { call ->
            val requestId = UUID.randomUUID().toString()
            call.attributes.put(RequestId, requestId)
            call.response.header("X-Request-ID", requestId)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("Referrer-Policy", "no-referrer")
            call.response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
            if (!config.development) call.response.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
            if (!call.request.path().startsWith("/health/")) limiter.consume("all:${call.clientAddress()}", 240)
        }
    })
    install(RequestBodyLimit) { bodyLimit { call -> if (call.request.path() == "/v1/sync") 1_048_576 else 8_192 } }
    if (config.corsOrigins.isNotEmpty()) install(CORS) {
        config.corsOrigins.forEach { origin -> val uri = URI(origin); allowHost(uri.rawAuthority, schemes = listOf(uri.scheme)) }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Treasury-CSRF")
        allowMethod(HttpMethod.Delete)
        allowCredentials = true
    }

    suspend fun ApplicationCall.session(): SessionRecord {
        val authorization = request.headers[HttpHeaders.Authorization]
        val token = if (authorization != null) {
            if (!authorization.startsWith("Bearer ", ignoreCase = true)) unauthorized()
            authorization.substring(7)
        } else {
            val cookie = request.cookies[oauth.sessionCookie] ?: unauthorized()
            if (request.httpMethod !in listOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)) {
                val origin = request.headers[HttpHeaders.Origin]
                if (request.headers["X-Treasury-CSRF"] != "1" || origin == null || origin !in (config.corsOrigins + config.publicUrl))
                    throw ApiException(HttpStatusCode.Forbidden, "csrf_rejected", "Request origin could not be verified")
            }
            cookie
        }
        val session = auth.authenticate(token) ?: unauthorized()
        limiter.consume("owner:${session.ownerId}", 240)
        return session
    }
    fun ApplicationCall.authLimit() = limiter.consume("auth:${clientAddress()}", 15)
    fun requireMail() { if (mailSender == null) throw ApiException(HttpStatusCode.ServiceUnavailable, "email_unavailable", "Email delivery is not configured on this server") }

    routing {
        get("/health/live") { call.respond(StatusResponse("ok")) }
        get("/health/ready") {
            val ready = store.ready()
            call.respond(if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, StatusResponse(if (ready) "ready" else "unavailable"))
        }
        route("/v1") {
            post("/auth/register") {
                call.authLimit()
                val body = call.receive<CredentialsRequest>()
                call.respond(HttpStatusCode.Created, auth.register(body.email, body.password))
            }
            post("/auth/login") {
                call.authLimit()
                val body = call.receive<CredentialsRequest>()
                limiter.consume("email:${Secrets.hash(body.email.trim().lowercase())}", 15)
                call.respond(auth.login(body.email, body.password))
            }
            post("/auth/reauthenticate") {
                call.authLimit()
                val session = call.session()
                call.respond(auth.reauthenticate(session.ownerId, call.receive<PasswordRequest>().password))
            }
            post("/auth/password-reset/request") {
                call.authLimit()
                requireMail()
                val email = normalizeEmail(call.receive<EmailRequest>().email)
                limiter.consume("recovery:${Secrets.hash(email)}", 3, 3_600_000)
                recovery.requestReset(email)
                call.respond(HttpStatusCode.Accepted, StatusResponse("If the account exists, a recovery code will be emailed"))
            }
            post("/auth/password-reset/complete") {
                call.authLimit()
                val body = call.receive<ResetPasswordRequest>()
                recovery.reset(body.token, body.password)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/auth/email-status") {
                call.respond(EmailStatusResponse(mailSender != null, store.emailVerified(call.session().ownerId)))
            }
            post("/auth/email-verification/request") {
                val session = call.session()
                requireMail()
                limiter.consume("verify:${session.ownerId}", 3, 3_600_000)
                recovery.requestVerification(session.ownerId)
                call.respond(HttpStatusCode.Accepted, StatusResponse("A verification code will be emailed"))
            }
            post("/auth/email-verification/complete") {
                call.authLimit()
                recovery.verify(call.receive<CodeRequest>().token)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/auth/me") {
                val session = call.session()
                val user = store.findUser(session.ownerId) ?: unauthorized()
                call.respond(UserResponse(user.id, user.email))
            }
            post("/auth/logout") {
                store.revokeSession(call.session().hash)
                oauth.clearSession(call)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/auth/logout-all") {
                store.revokeAllSessions(call.session().ownerId)
                oauth.clearSession(call)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/auth/providers") { call.respond(ProvidersResponse(config.oauthProviders.keys.sorted())) }
            post("/auth/oauth/device") { call.authLimit(); call.respond(HttpStatusCode.Created, oauth.createDevice(call.receive<OAuthDeviceRequest>().provider)) }
            get("/auth/oauth/device/start") { call.authLimit(); oauth.startDevice(call) }
            post("/auth/oauth/device/approve") { call.authLimit(); oauth.approveDevice(call) }
            post("/auth/oauth/device/poll") {
                val body = call.receive<OAuthDevicePollRequest>()
                limiter.consume("poll:${Secrets.hash(body.attemptId)}", 30)
                oauth.pollDevice(call, body)
            }
            get("/auth/oauth/{provider}/start") { call.authLimit(); oauth.start(call, call.parameters["provider"].orEmpty()) }
            get("/auth/oauth/{provider}/callback") { call.authLimit(); oauth.callback(call, call.parameters["provider"].orEmpty()) }
            get("/sync") {
                val owner = call.session().ownerId
                val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: invalid("Invalid page limit") } ?: 500
                if (limit !in 1..500) invalid("Page limit must be between 1 and 500")
                call.respond(store.pull(owner, cursorTime(call.request.queryParameters["cursor"]), limit))
            }
            post("/sync") {
                val owner = call.session().ownerId
                call.respond(store.push(owner, call.receive<TreasurySnapshot>(), clock.millis()))
            }
            get("/account/export") {
                val owner = call.session().ownerId
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=treasury-export.json")
                call.respond(store.export(owner))
            }
            delete("/account") {
                val session = call.session()
                if (clock.millis() - session.createdAt > 600_000) throw ApiException(HttpStatusCode.Forbidden, "reauthentication_required", "Sign in again before erasing your account")
                if (call.receive<EraseRequest>().confirmation != "DELETE_MY_ACCOUNT") invalid("Account erasure requires explicit confirmation")
                store.eraseAccount(session.ownerId)
                oauth.clearSession(call)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
