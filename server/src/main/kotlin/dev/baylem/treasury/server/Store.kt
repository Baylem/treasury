package dev.baylem.treasury.server

import dev.baylem.treasury.domain.*
import dev.baylem.treasury.repository.entities
import dev.baylem.treasury.repository.mergeSnapshots
import dev.baylem.treasury.repository.treasuryJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class UserRecord(val id: String, val email: String?, val passwordHash: String?, val createdAt: Long)
data class SessionRecord(val hash: String, val ownerId: String, val createdAt: Long, val expiresAt: Long)
data class OAuthFlow(val stateHash: String, val provider: String, val verifier: String, val expiresAt: Long, val deviceAttemptId: String? = null)
data class OAuthDevice(val id: String, val secretHash: String, val codeHash: String, val provider: String, val expiresAt: Long)
sealed interface DevicePoll {
    data object Pending : DevicePoll
    data object Expired : DevicePoll
    data class Approved(val ownerId: String) : DevicePoll
}

@Serializable data class SyncPage(val snapshot: TreasurySnapshot, val cursor: String, val hasMore: Boolean)
@Serializable data class PushResult(val snapshot: TreasurySnapshot)

interface ServerStore : AutoCloseable {
    suspend fun ready(): Boolean
    suspend fun findUserByEmail(email: String): UserRecord?
    suspend fun findUser(id: String): UserRecord?
    suspend fun createUser(user: UserRecord): Boolean
    suspend fun oauthUser(provider: String, subject: String, now: Long): UserRecord
    suspend fun addSession(session: SessionRecord, expectedPasswordHash: String? = null)
    suspend fun session(hash: String, now: Long): SessionRecord?
    suspend fun revokeSession(hash: String)
    suspend fun revokeAllSessions(ownerId: String)
    suspend fun addOAuthFlow(flow: OAuthFlow)
    suspend fun consumeOAuthFlow(hash: String, provider: String, now: Long): OAuthFlow?
    suspend fun queueChallenge(challenge: AuthChallenge, mail: OutgoingMail, now: Long)
    suspend fun challengeExists(hash: String, purpose: String, now: Long): Boolean
    suspend fun redeemChallenge(hash: String, purpose: String, passwordHash: String?, now: Long): Boolean
    suspend fun emailVerified(ownerId: String): Boolean
    suspend fun claimMail(now: Long): List<OutgoingMail>
    suspend fun acknowledgeMail(tokenHash: String)
    suspend fun pruneExpired(now: Long)
    suspend fun createDeviceAttempt(device: OAuthDevice, now: Long)
    suspend fun deviceAttempt(id: String, now: Long): OAuthDevice?
    suspend fun stageDeviceAttempt(id: String, ownerId: String, approvalHash: String, now: Long): Boolean
    suspend fun approveDeviceAttempt(id: String, approvalHash: String, codeHash: String, now: Long): Boolean
    suspend fun consumeDeviceAttempt(id: String, secretHash: String, now: Long): DevicePoll
    suspend fun pull(ownerId: String, after: Long, limit: Int): SyncPage
    suspend fun push(ownerId: String, incoming: TreasurySnapshot, now: Long): PushResult
    suspend fun export(ownerId: String): TreasurySnapshot
    /** Account deletion invalidates all devices first, then removes financial rows and tombstones. */
    suspend fun eraseAccount(ownerId: String)
}

internal data class EncodedRecord(val id: String, val kind: String, val payload: String)

internal fun TreasurySnapshot.encodeRecords(): List<EncodedRecord> =
    accounts.map { EncodedRecord(it.id, "account", treasuryJson.encodeToString(it)) } +
    entries.map { EncodedRecord(it.id, "entry", treasuryJson.encodeToString(it)) } +
    plans.map { EncodedRecord(it.id, "plan", treasuryJson.encodeToString(it)) } +
    overrides.map { EncodedRecord(it.id, "override", treasuryJson.encodeToString(it)) }

internal fun List<EncodedRecord>.decodeSnapshot(): TreasurySnapshot = TreasurySnapshot(
    accounts = filter { it.kind == "account" }.map { treasuryJson.decodeFromString<Account>(it.payload) },
    entries = filter { it.kind == "entry" }.map { treasuryJson.decodeFromString<FinancialEntry>(it.payload) },
    plans = filter { it.kind == "plan" }.map { treasuryJson.decodeFromString<PaymentPlan>(it.payload) },
    overrides = filter { it.kind == "override" }.map { treasuryJson.decodeFromString<OccurrenceOverride>(it.payload) },
)

internal fun mergeForServer(local: TreasurySnapshot, incoming: TreasurySnapshot, ownerId: String, now: Long): TreasurySnapshot {
    if (incoming.entities().size > 500) invalid("Sync batches may contain at most 500 entities")
    val existing = local.entities().associateBy { it.id }
    incoming.entities().forEach { entity ->
        if (entity.ownerId != ownerId) throw ApiException(io.ktor.http.HttpStatusCode.Forbidden, "owner_mismatch", "Data belongs to another account")
        if (!isUuid(entity.id)) invalid("Entity IDs must be UUIDs")
        if (entity.updatedAt.toEpochMilliseconds() > now + 300_000) invalid("Device clock is more than five minutes ahead")
        existing[entity.id]?.let {
            if (it.createdAt != entity.createdAt || it::class != entity::class) invalid("Entity creation time and type are immutable")
        }
    }
    val result = try { mergeSnapshots(local, incoming, ownerId) } catch (_: IllegalArgumentException) { invalid("Sync data contains invalid or missing relationships") }
    if (result.entities().size > 50_000) invalid("Account entity limit of 50,000 reached")
    return result
}

internal fun isUuid(value: String): Boolean = try { UUID.fromString(value).toString() == value } catch (_: IllegalArgumentException) { false }
fun cursorTime(value: String?): Long = value?.let {
    try { Instant.parse(it).toEpochMilli().also { time -> if (time < 0) invalid("Invalid sync cursor") } }
    catch (_: java.time.DateTimeException) { invalid("Invalid sync cursor") }
    catch (_: ArithmeticException) { invalid("Invalid sync cursor") }
} ?: 0
internal fun cursorString(time: Long): String = Instant.ofEpochMilli(time).toString()

class AuthService(
    private val store: ServerStore,
    private val passwords: PasswordHasher = Argon2Passwords(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private var dummyHash: String? = null
    suspend fun register(email: String, password: String): SessionResponse {
        val normalized = normalizeEmail(email)
        validatePassword(password)
        val user = UserRecord(UUID.randomUUID().toString(), normalized, passwords.hash(password), clock.millis())
        if (!store.createUser(user)) throw ApiException(io.ktor.http.HttpStatusCode.Conflict, "account_exists", "An account with this email already exists")
        return createSession(user.id, user.passwordHash)
    }
    suspend fun login(email: String, password: String): SessionResponse {
        val normalized = normalizeEmail(email)
        if (password.length > 128 || password.toByteArray().size > 512) unauthorized()
        val user = store.findUserByEmail(normalized)
        // Unknown users still perform Argon2 verification; no cheap account-existence timing branch.
        val fallback = dummyHash ?: passwords.hash(Secrets.token()).also { dummyHash = it }
        val valid = passwords.verify(user?.passwordHash ?: fallback, password)
        if (user == null || user.passwordHash == null || !valid) unauthorized()
        return createSession(user.id, user.passwordHash)
    }
    suspend fun createSession(ownerId: String, expectedPasswordHash: String? = null): SessionResponse {
        val token = Secrets.token()
        val now = clock.millis()
        val expires = now + 30L * 24 * 60 * 60 * 1000
        store.addSession(SessionRecord(Secrets.hash(token), ownerId, now, expires), expectedPasswordHash)
        return SessionResponse(token, ownerId, cursorString(expires))
    }
    suspend fun authenticate(token: String): SessionRecord? = if (token.length == 43) store.session(Secrets.hash(token), clock.millis()) else null
    suspend fun reauthenticate(ownerId: String, password: String): SessionResponse {
        val user = store.findUser(ownerId) ?: unauthorized()
        if (password.length > 128 || user.passwordHash == null || !passwords.verify(user.passwordHash, password)) unauthorized()
        return createSession(ownerId, user.passwordHash)
    }
}

@Serializable data class CredentialsRequest(val email: String, val password: String)
@Serializable data class PasswordRequest(val password: String)
@Serializable data class EraseRequest(val confirmation: String)
@Serializable data class SessionResponse(val token: String, val ownerId: String, val expiresAt: String)
@Serializable data class UserResponse(val ownerId: String, val email: String?)
@Serializable data class ErrorResponse(val code: String, val message: String, val requestId: String)
@Serializable data class StatusResponse(val status: String)
@Serializable data class ProvidersResponse(val providers: List<String>)
