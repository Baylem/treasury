package dev.baylem.treasury.sync

import dev.baylem.treasury.domain.TreasurySnapshot
import kotlinx.serialization.Serializable

/** Transport seam; credentials never enter financial snapshots, backups, or repository storage. */
interface TreasuryRemote {
    suspend fun register(email: String, password: String): RemoteSession
    suspend fun login(email: String, password: String): RemoteSession
    suspend fun me(session: RemoteSession): RemoteUser
    suspend fun logout(session: RemoteSession)
    suspend fun logoutAll(session: RemoteSession)
    suspend fun reauthenticate(session: RemoteSession, password: String): RemoteSession
    /** Permanently erases the remote account and tombstones; requires a recent server session. */
    suspend fun eraseAccount(session: RemoteSession, confirmation: String)
    suspend fun requestPasswordReset(email: String)
    suspend fun completePasswordReset(token: String, password: String)
    suspend fun emailStatus(session: RemoteSession): EmailVerificationStatus
    suspend fun requestEmailVerification(session: RemoteSession)
    suspend fun completeEmailVerification(token: String)
    suspend fun providers(): List<String>
    suspend fun beginOAuthDevice(provider: String): OAuthDeviceAttempt
    /** Null means pending. Poll at least three seconds apart, until the attempt expires. */
    suspend fun pollOAuthDevice(attemptId: String, pollSecret: String): RemoteSession?
    suspend fun pull(session: RemoteSession, cursor: String? = null, limit: Int = 500): RemoteSyncPage
    suspend fun push(session: RemoteSession, snapshot: TreasurySnapshot): RemotePushResult
    suspend fun exportAccount(session: RemoteSession): TreasurySnapshot
    fun close()
}

@Serializable
data class RemoteSession(val token: String, val ownerId: String, val expiresAt: String) {
    override fun toString(): String = "RemoteSession(ownerId=$ownerId, expiresAt=$expiresAt, token=<redacted>)"
}

@Serializable data class RemoteUser(val ownerId: String, val email: String? = null)
@Serializable data class EmailVerificationStatus(val deliveryAvailable: Boolean, val verified: Boolean)
@Serializable
data class OAuthDeviceAttempt(val attemptId: String, val pollSecret: String, val authorizationUrl: String,
    val verificationCode: String, val expiresAt: String) {
    override fun toString(): String = "OAuthDeviceAttempt(attemptId=$attemptId, expiresAt=$expiresAt, secrets=<redacted>)"
}
@Serializable data class RemoteSyncPage(val snapshot: TreasurySnapshot, val cursor: String, val hasMore: Boolean)
@Serializable data class RemotePushResult(val snapshot: TreasurySnapshot)
@Serializable internal data class CredentialsRequest(val email: String, val password: String)
@Serializable internal data class PasswordRequest(val password: String)
@Serializable internal data class EraseRequest(val confirmation: String)
@Serializable internal data class EmailRequest(val email: String)
@Serializable internal data class PasswordResetRequest(val token: String, val password: String)
@Serializable internal data class TokenRequest(val token: String)
@Serializable internal data class ProvidersResponse(val providers: List<String>)
@Serializable internal data class OAuthDeviceRequest(val provider: String)
@Serializable internal data class OAuthPollRequest(val attemptId: String, val pollSecret: String)
@Serializable internal data class RemoteError(val code: String, val message: String, val requestId: String = "")

class RemoteException(val statusCode: Int, val code: String, message: String) : IllegalStateException(message)
