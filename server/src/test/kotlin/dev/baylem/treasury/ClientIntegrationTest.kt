package dev.baylem.treasury

import dev.baylem.treasury.domain.*
import dev.baylem.treasury.repository.*
import dev.baylem.treasury.server.MailSender
import dev.baylem.treasury.server.OutgoingMail
import dev.baylem.treasury.server.ServerConfig
import dev.baylem.treasury.sync.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

/** The shipping shared HTTP client, repository and synchronizer against the actual API and SQL. */
class ClientIntegrationTest {
    private val serverClock = Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC)
    private val clientNow = kotlin.time.Instant.parse("2026-09-06T12:00:00Z")
    private val config = ServerConfig("jdbc:postgresql://localhost/test", "test", "test", "http://localhost", development = true)

    @Test fun twoShippingClientsConvergeIncludingOverridesTombstonesAndOwnerIsolation() = testApplication {
        application { module(config, testStore(), FastPasswords(), serverClock) }
        val remoteA = KtorTreasuryRemote("http://localhost", createClient { followRedirects = false })
        val remoteB = KtorTreasuryRemote("http://localhost", createClient { followRedirects = false })
        val sessionA = remoteA.register("alice@example.com", "a very strong password")
        val sessionB = remoteB.login("alice@example.com", "a very strong password")
        val first = LocalRepository(sessionA.ownerId, MemoryDataStore(), now = { clientNow })
        val second = LocalRepository(sessionB.ownerId, MemoryDataStore(), now = { clientNow })
        first.initialize(); second.initialize()
        val account = Account(first.createEntityMeta(), "Checking", openingBalanceMinor = 125_000, balanceDate = LocalDate(2026, 9, 1))
        first.saveAccount(account)
        val salary = FinancialEntry(first.createEntityMeta(), account.id, "Salary", 240_000, EntryDirection.INCOME, LocalDate(2026, 9, 1), recurrence = RecurrenceRule(Frequency.MONTHLY))
        first.saveEntry(salary)
        val syncA = RepositorySynchronizer(first, remoteA, sessionA, now = { clientNow })
        val syncB = RepositorySynchronizer(second, remoteB, sessionB, now = { clientNow })
        suspend fun sync(value: RepositorySynchronizer) { value.sync(); assertIs<SyncState.Success>(value.state.value) }
        sync(syncA); sync(syncB)
        assertEquals(first.state.value, second.state.value)
        second.saveOverride(OccurrenceOverride(second.createEntityMeta(), salary.id, LocalDate(2026, 10, 1), OverrideAction.Replace(LocalDate(2026, 10, 2), 245_001)))
        sync(syncB); sync(syncA)
        assertEquals(first.state.value, second.state.value)
        first.softDelete(EntityKind.ENTRY, salary.id)
        sync(syncA); sync(syncB)
        assertEquals(first.state.value, second.state.value)
        val final = remoteA.exportAccount(sessionA)
        assertNotNull(final.entries.single().deletedAt)
        assertNotNull(final.overrides.single().deletedAt)
        assertEquals(245_001L, (final.overrides.single().action as OverrideAction.Replace).amountMinor)
        val bob = remoteB.register("bob@example.com", "another strong password")
        assertTrue(remoteB.exportAccount(bob).entities().isEmpty())
        val fresh = remoteA.reauthenticate(sessionA, "a very strong password")
        remoteA.eraseAccount(fresh, "DELETE_MY_ACCOUNT")
        val erased = assertFailsWith<RemoteException> { remoteB.me(sessionB) }
        assertEquals(401, erased.statusCode)
        assertEquals(bob.ownerId, remoteB.me(bob).ownerId)
        first.close(); second.close(); remoteA.close(); remoteB.close()
    }

    @Test fun shippingClientVerificationAndPasswordRecoveryWorkAndRevokeExistingSession() = testApplication {
        val delivered = Channel<OutgoingMail>(Channel.UNLIMITED)
        val sender = object : MailSender { override suspend fun send(mail: OutgoingMail) { delivered.send(mail) } }
        application { module(config, testStore(), FastPasswords(), serverClock, mailSender = sender) }
        val remote = KtorTreasuryRemote("http://localhost", createClient { followRedirects = false })
        val session = remote.register("person@example.com", "a very strong password")
        assertTrue(remote.emailStatus(session).deliveryAvailable)
        assertFalse(remote.emailStatus(session).verified)
        remote.requestEmailVerification(session)
        val verifyMail = withTimeout(20_000) { delivered.receive() }
        remote.completeEmailVerification(Regex("[A-Za-z0-9_-]{43}").find(verifyMail.body)!!.value)
        assertTrue(remote.emailStatus(session).verified)
        remote.requestPasswordReset("person@example.com")
        val resetMail = withTimeout(20_000) { delivered.receive() }
        remote.completePasswordReset(Regex("[A-Za-z0-9_-]{43}").find(resetMail.body)!!.value, "a different strong password")
        assertEquals(401, assertFailsWith<RemoteException> { remote.me(session) }.statusCode)
        assertEquals(session.ownerId, remote.login("person@example.com", "a different strong password").ownerId)
        remote.close()
    }
}
