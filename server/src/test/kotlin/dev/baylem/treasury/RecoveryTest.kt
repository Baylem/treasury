package dev.baylem.treasury

import dev.baylem.treasury.server.*
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class RecoveryTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC)
    @Test fun passwordResetIsSingleUseRevokesSessionsAndNeverStoresPlaintextCredential() = runBlocking {
        val store = testStore()
        val passwords = FastPasswords()
        val user = UserRecord(UUID.randomUUID().toString(), "person@example.com", passwords.hash("old secure password"), clock.millis())
        store.createUser(user)
        val session = AuthService(store, passwords, clock).createSession(user.id)
        val recovery = RecoveryService(store, passwords, clock)
        recovery.requestReset(user.email!!)
        val mail = store.claimMail(clock.millis()).single()
        val token = Regex("[A-Za-z0-9_-]{43}").find(mail.body)!!.value
        assertEquals(Secrets.hash(token), mail.tokenHash)
        assertTrue(store.claimMail(clock.millis()).isEmpty(), "Outbox leases prevent immediate duplicate deliveries")
        store.acknowledgeMail(mail.tokenHash)
        recovery.reset(token, "new secure password")
        assertNull(store.session(Secrets.hash(session.token), clock.millis()))
        assertTrue(passwords.verify(store.findUser(user.id)!!.passwordHash!!, "new secure password"))
        assertTrue(store.emailVerified(user.id))
        assertFailsWith<ApiException> { recovery.reset(token, "another secure password") }
        Unit
    }

    @Test fun expiredWrongPurposeAndUnknownCodesCannotChangePassword() = runBlocking {
        val store = testStore()
        val user = UserRecord(UUID.randomUUID().toString(), "person@example.com", "hash", clock.millis())
        store.createUser(user)
        val recovery = RecoveryService(store, FastPasswords(), clock)
        recovery.requestReset("unknown@example.com")
        assertTrue(store.claimMail(clock.millis()).isEmpty())
        recovery.requestVerification(user.id)
        val mail = store.claimMail(clock.millis()).single()
        val token = Regex("[A-Za-z0-9_-]{43}").find(mail.body)!!.value
        assertFailsWith<ApiException> { recovery.reset(token, "new secure password") }
        assertFalse(store.redeemChallenge(mail.tokenHash, "verify", null, clock.millis() + 1_800_000))
        recovery.verify(token)
        assertTrue(store.emailVerified(user.id))
        assertFailsWith<ApiException> { recovery.verify(token) }
        Unit
    }

    @Test fun aNewCodeInvalidatesPreviousCodeAndPendingMail() = runBlocking {
        val store = testStore()
        val user = UserRecord(UUID.randomUUID().toString(), "person@example.com", "hash", clock.millis())
        store.createUser(user)
        val recovery = RecoveryService(store, FastPasswords(), clock)
        recovery.requestReset(user.email!!)
        val first = store.claimMail(clock.millis()).single()
        recovery.requestReset(user.email)
        val second = store.claimMail(clock.millis()).single()
        assertNotEquals(first.tokenHash, second.tokenHash)
        assertFalse(store.challengeExists(first.tokenHash, "reset", clock.millis()))
        store.eraseAccount(user.id)
        assertTrue(store.claimMail(clock.millis() + 180_000).isEmpty())
    }
}
