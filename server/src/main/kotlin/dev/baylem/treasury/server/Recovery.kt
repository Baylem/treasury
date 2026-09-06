package dev.baylem.treasury.server

import io.ktor.http.HttpStatusCode
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Clock
import java.util.Properties

data class OutgoingMail(val tokenHash: String, val recipient: String, val subject: String, val body: String)
data class AuthChallenge(val tokenHash: String, val ownerId: String, val purpose: String, val expiresAt: Long)

interface MailSender { suspend fun send(mail: OutgoingMail) }

class SmtpMailSender(private val config: MailConfig) : MailSender {
    override suspend fun send(mail: OutgoingMail) = withContext(Dispatchers.IO) {
        val properties = Properties().apply {
            setProperty("mail.smtp.host", config.host)
            setProperty("mail.smtp.port", config.port.toString())
            setProperty("mail.smtp.auth", "true")
            setProperty("mail.smtp.connectiontimeout", "10000")
            setProperty("mail.smtp.timeout", "10000")
            setProperty("mail.smtp.writetimeout", "10000")
            setProperty("mail.smtp.ssl.checkserveridentity", "true")
            if (config.tls == "tls") setProperty("mail.smtp.ssl.enable", "true") else {
                setProperty("mail.smtp.starttls.enable", "true")
                setProperty("mail.smtp.starttls.required", "true")
            }
        }
        val session = Session.getInstance(properties)
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.from, true))
            setRecipient(Message.RecipientType.TO, InternetAddress(mail.recipient, true))
            setSubject(mail.subject, "UTF-8")
            setText(mail.body, "UTF-8")
        }
        Transport.send(message, config.username, config.password)
    }
}

class RecoveryService(private val store: ServerStore, private val passwords: PasswordHasher, private val clock: Clock) {
    private suspend fun issue(user: UserRecord, purpose: String) {
        val email = user.email ?: return
        val token = Secrets.token()
        val hash = Secrets.hash(token)
        val label = if (purpose == "reset") "Reset your Treasury password" else "Verify your Treasury email"
        val body = "$label\n\nYour single-use code is:\n\n$token\n\nPaste this code into Treasury. It expires in 30 minutes. If you did not request this, you can ignore this message. Never share this code."
        store.queueChallenge(AuthChallenge(hash, user.id, purpose, clock.millis() + 1_800_000), OutgoingMail(hash, email, label, body), clock.millis())
    }
    suspend fun requestReset(email: String) {
        val user = store.findUserByEmail(normalizeEmail(email)) ?: return
        if (user.passwordHash != null) issue(user, "reset")
    }
    suspend fun requestVerification(ownerId: String) { store.findUser(ownerId)?.let { issue(it, "verify") } }
    suspend fun reset(token: String, password: String) {
        if (token.length != 43) invalidCode()
        validatePassword(password)
        if (!store.challengeExists(Secrets.hash(token), "reset", clock.millis())) invalidCode()
        val hash = passwords.hash(password)
        if (!store.redeemChallenge(Secrets.hash(token), "reset", hash, clock.millis())) invalidCode()
    }
    suspend fun verify(token: String) {
        if (token.length != 43 || !store.redeemChallenge(Secrets.hash(token), "verify", null, clock.millis())) invalidCode()
    }
    private fun invalidCode(): Nothing = throw ApiException(HttpStatusCode.BadRequest, "invalid_code", "The code is invalid or expired")
}

@Serializable data class EmailRequest(val email: String)
@Serializable data class CodeRequest(val token: String)
@Serializable data class ResetPasswordRequest(val token: String, val password: String)
@Serializable data class EmailStatusResponse(val deliveryAvailable: Boolean, val verified: Boolean)
