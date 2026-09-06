package dev.baylem.treasury.server

import de.mkammerer.argon2.Argon2Factory
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.Locale

class ApiException(val status: HttpStatusCode, val code: String, override val message: String, val retryAfterSeconds: Long? = null) : RuntimeException(message)

fun invalid(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, "invalid_request", message)
fun unauthorized(): Nothing = throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Authentication is required or has expired")

object Secrets {
    private val random = SecureRandom()
    fun token(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun verificationCode(): String = random.nextInt(100_000_000).toString().padStart(8, '0')
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

interface PasswordHasher {
    suspend fun hash(password: String): String
    suspend fun verify(hash: String, password: String): Boolean
}

class Argon2Passwords : PasswordHasher {
    private val dispatcher = Dispatchers.IO.limitedParallelism(2)
    override suspend fun hash(password: String): String = withContext(dispatcher) {
        val argon = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
        val characters = password.toCharArray()
        try { argon.hash(3, 65_536, 1, characters) } finally { argon.wipeArray(characters) }
    }
    override suspend fun verify(hash: String, password: String): Boolean = withContext(dispatcher) {
        val argon = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
        val characters = password.toCharArray()
        try { argon.verify(hash, characters) } finally { argon.wipeArray(characters) }
    }
}

fun normalizeEmail(value: String): String {
    val email = value.trim().lowercase(Locale.ROOT)
    if (email.length !in 3..254 || !Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}$").matches(email)) invalid("Enter a valid email address")
    return email
}

fun validatePassword(value: String) {
    if (value.length !in 12..128 || value.toByteArray(Charsets.UTF_8).size > 512) invalid("Passwords must contain 12 to 128 characters")
}

/** Bounded, expiring fixed windows. Cluster deployments also need a shared edge rate limit. */
class RequestLimiter(private val clock: Clock = Clock.systemUTC(), private val maxKeys: Int = 20_000) {
    private data class Window(val until: Long, var used: Int)
    private val windows = LinkedHashMap<String, Window>()
    @Synchronized fun consume(key: String, limit: Int, windowMillis: Long = 60_000) {
        val now = clock.millis()
        if (windows.size >= maxKeys) windows.entries.removeIf { it.value.until <= now }
        val window = windows[key]?.takeIf { it.until > now } ?: run {
            if (windows.size >= maxKeys) throw ApiException(HttpStatusCode.TooManyRequests, "rate_limited", "Please try again in a minute")
            Window(now + windowMillis, 0).also { windows[key] = it }
        }
        if (++window.used > limit) throw ApiException(HttpStatusCode.TooManyRequests, "rate_limited", "Too many requests; please try again later", (window.until - now + 999) / 1000)
    }
}

/** Parse only IP literals; an untrusted forwarding header must never trigger a DNS lookup. */
internal fun parseIpAddress(value: String): String? =
    if (value.length !in 2..45 || !value.all { it in "0123456789abcdefABCDEF:." } || ':' !in value && !value.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) null
    else try { java.net.InetAddress.getByName(value).hostAddress } catch (_: Exception) { null }

internal fun clientAddress(peer: String, forwardedFor: String?, trusted: Set<String>): String {
    var address = parseIpAddress(peer) ?: peer
    for (candidate in forwardedFor.orEmpty().split(',').asReversed()) {
        if (address !in trusted) break
        address = parseIpAddress(candidate.trim()) ?: break
    }
    return address
}
