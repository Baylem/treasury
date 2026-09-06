package dev.baylem.treasury.application

import dev.baylem.treasury.domain.Account
import dev.baylem.treasury.domain.TreasurySnapshot
import dev.baylem.treasury.repository.DataStore
import dev.baylem.treasury.repository.LocalRepository
import dev.baylem.treasury.repository.MemoryDataStore
import dev.baylem.treasury.repository.Repository
import dev.baylem.treasury.repository.RepositoryState
import dev.baylem.treasury.repository.entities
import dev.baylem.treasury.repository.mergeSnapshots
import dev.baylem.treasury.sync.RemotePushResult
import dev.baylem.treasury.sync.EmailVerificationStatus
import dev.baylem.treasury.sync.OAuthDeviceAttempt
import dev.baylem.treasury.sync.RemoteSession
import dev.baylem.treasury.sync.RemoteSyncPage
import dev.baylem.treasury.sync.RemoteUser
import dev.baylem.treasury.sync.TreasuryRemote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TreasuryControllerTest {
    private fun Repository.snapshot() = assertIs<RepositoryState.Ready>(state.value).snapshot

    private inner class Fixture {
        val disk = MemoryDataStore()
        val otherServerDisks = mutableMapOf<String, MemoryDataStore>()
        val events = mutableListOf<String>()
        val remoteSnapshots = mutableMapOf<String, TreasurySnapshot>()
        val otherRemoteSnapshots = mutableMapOf<String, MutableMap<String, TreasurySnapshot>>()
        val clients = mutableListOf<FakeRemote>()
        var failAuthentication = false
        var failAccountStorage = false
        var failRemoteErase = false
        var failLogout = false
        var oauthReady = false
        var oauthAuthorizationUrl: String? = null
        var pullGate: CompletableDeferred<Unit>? = null
        val pullEntered = CompletableDeferred<Unit>()
        val controller = TreasuryController(
            repositoryFactory = { owner, server ->
                val scopedDisk = if (server == null || server == "https://treasury.example") disk
                    else otherServerDisks.getOrPut(server) { MemoryDataStore() }
                LocalRepository(owner, object : DataStore {
                    override suspend fun read(ownerId: String): TreasurySnapshot {
                        if (ownerId != "local" && failAccountStorage) error("Account database unavailable")
                        return scopedDisk.read(ownerId)
                    }
                    override suspend fun write(ownerId: String, snapshot: TreasurySnapshot) = scopedDisk.write(ownerId, snapshot)
                    override suspend fun purgeOwner(ownerId: String) {
                        events += "purge:$ownerId"
                        scopedDisk.purgeOwner(ownerId)
                    }
                    override suspend fun close() { events += "close:$owner" }
                })
            },
            remoteFactory = { server -> FakeRemote(server).also(clients::add) },
        )

        suspend fun addGuestAccount(): Repository {
            val guest = controller.repository.value
            guest.initialize()
            guest.saveAccount(Account(guest.createEntityMeta(), "Guest account", balanceDate = LocalDate(2026, 1, 1)))
            return guest
        }

        suspend fun connect(includeLocal: Boolean = false, email: String = "first@example.com", register: Boolean = false, server: String = "https://treasury.example") =
            controller.connect(server, email, "strong-test-password", register, includeLocal)

        inner class FakeRemote(private val server: String) : TreasuryRemote {
            private val serverSnapshots = if (server == "https://treasury.example") remoteSnapshots
                else otherRemoteSnapshots.getOrPut(server) { mutableMapOf() }
            val pushes = mutableListOf<TreasurySnapshot>()
            var closed = false
            private fun newSession(email: String) = RemoteSession("token", email.substringBefore('@'), "2099-01-01T00:00:00Z")
            override suspend fun register(email: String, password: String): RemoteSession {
                events += "register"
                if (failAuthentication) error("Incorrect credentials")
                return newSession(email)
            }
            override suspend fun login(email: String, password: String): RemoteSession {
                events += "login"
                if (failAuthentication) error("Incorrect credentials")
                return newSession(email)
            }
            override suspend fun me(session: RemoteSession) = RemoteUser(session.ownerId)
            override suspend fun logout(session: RemoteSession) {
                events += "logout:${session.ownerId}"
                if (failLogout) error("Server offline")
            }
            override suspend fun logoutAll(session: RemoteSession) = logout(session)
            override suspend fun reauthenticate(session: RemoteSession, password: String): RemoteSession {
                events += "reauthenticate:${session.ownerId}"
                return session.copy(token = "fresh-token")
            }
            override suspend fun eraseAccount(session: RemoteSession, confirmation: String) {
                assertEquals("fresh-token", session.token)
                assertEquals("DELETE_MY_ACCOUNT", confirmation)
                if (failRemoteErase) error("Account erasure unavailable")
                events += "remote-erase:${session.ownerId}"
                serverSnapshots.remove(session.ownerId)
            }
            override suspend fun requestPasswordReset(email: String) { events += "request-reset" }
            override suspend fun completePasswordReset(token: String, password: String) { events += "complete-reset" }
            override suspend fun emailStatus(session: RemoteSession) = EmailVerificationStatus(true, false)
            override suspend fun requestEmailVerification(session: RemoteSession) = Unit
            override suspend fun completeEmailVerification(token: String) = Unit
            override suspend fun providers() = listOf("google", "github", "discord")
            override suspend fun beginOAuthDevice(provider: String) = OAuthDeviceAttempt("attempt", "private-poll-secret",
                oauthAuthorizationUrl ?: "$server/v1/auth/oauth/device/authorize?attempt=attempt", "12345678", "2099-01-01T00:00:00Z")
            override suspend fun pollOAuthDevice(attemptId: String, pollSecret: String): RemoteSession? {
                assertEquals("attempt", attemptId)
                assertEquals("private-poll-secret", pollSecret)
                return if (oauthReady) newSession("first@example.com") else null
            }
            override suspend fun pull(session: RemoteSession, cursor: String?, limit: Int): RemoteSyncPage {
                pullEntered.complete(Unit)
                pullGate?.await()
                return RemoteSyncPage(serverSnapshots[session.ownerId] ?: TreasurySnapshot(), "2026-09-06T00:00:00Z", false)
            }
            override suspend fun push(session: RemoteSession, snapshot: TreasurySnapshot): RemotePushResult {
                events += "push:${session.ownerId}"
                pushes += snapshot
                val merged = mergeSnapshots(serverSnapshots[session.ownerId] ?: TreasurySnapshot(), snapshot, session.ownerId)
                serverSnapshots[session.ownerId] = merged
                val ids = snapshot.entities().map { it.id }.toSet()
                return RemotePushResult(merged.copy(accounts = merged.accounts.filter { it.id in ids },
                    entries = merged.entries.filter { it.id in ids }, plans = merged.plans.filter { it.id in ids },
                    overrides = merged.overrides.filter { it.id in ids }))
            }
            override suspend fun exportAccount(session: RemoteSession) = serverSnapshots[session.ownerId] ?: TreasurySnapshot()
            override fun close() { closed = true }
        }
    }

    @Test fun signingInDoesNotCopyGuestDataWithoutExplicitOptIn() = runTest {
        val fixture = Fixture()
        try {
            val guest = fixture.addGuestAccount()
            fixture.connect()
            assertEquals("first", fixture.controller.repository.value.ownerId)
            assertEquals(TreasurySnapshot(), fixture.controller.repository.value.snapshot())
            assertTrue(fixture.events.none { it.startsWith("push:") })
            assertEquals(1, guest.snapshot().accounts.size)
        } finally { fixture.controller.close() }
    }

    @Test fun explicitGuestCopySyncsToTheAccountAndSigningOutReturnsToGuestData() = runTest {
        val fixture = Fixture()
        try {
            val guest = fixture.addGuestAccount()
            fixture.connect(includeLocal = true, register = true)
            assertTrue("register" in fixture.events)
            assertEquals("first", fixture.remoteSnapshots.getValue("first").accounts.single().ownerId)
            fixture.controller.disconnect()
            assertSame(guest, fixture.controller.repository.value)
            assertIs<ConnectionState.Local>(fixture.controller.connection.value)
            assertEquals("local", guest.snapshot().accounts.single().ownerId)
            assertTrue(fixture.clients.single().closed)
        } finally { fixture.controller.close() }
    }

    @Test fun failedAuthenticationLeavesTheGuestProfileAndClosesTheClient() = runTest {
        val fixture = Fixture()
        try {
            val guest = fixture.addGuestAccount()
            fixture.failAuthentication = true
            assertFailsWith<IllegalStateException> { fixture.connect(includeLocal = true) }
            assertSame(guest, fixture.controller.repository.value)
            assertIs<ConnectionState.Local>(fixture.controller.connection.value)
            assertTrue(fixture.clients.single().closed)
            assertTrue(fixture.remoteSnapshots.isEmpty())
        } finally { fixture.controller.close() }
    }

    @Test fun failedAccountStorageLeavesGuestDataUsableAndClosesOpenedResources() = runTest {
        val fixture = Fixture()
        try {
            val guest = fixture.addGuestAccount()
            fixture.failAccountStorage = true
            assertFailsWith<IllegalStateException> { fixture.connect(includeLocal = true) }
            assertSame(guest, fixture.controller.repository.value)
            assertEquals(1, guest.snapshot().accounts.size)
            assertTrue("close:first" in fixture.events)
            assertTrue(fixture.clients.single().closed)
        } finally { fixture.controller.close() }
    }

    @Test fun switchingAccountsNeverCopiesThePreviousAccountsRows() = runTest {
        val fixture = Fixture()
        try {
            fixture.addGuestAccount()
            fixture.connect(includeLocal = true)
            fixture.controller.disconnect()
            fixture.connect(email = "second@example.com")
            assertEquals("second", fixture.controller.repository.value.ownerId)
            assertEquals(TreasurySnapshot(), fixture.controller.repository.value.snapshot())
            assertEquals(1, fixture.remoteSnapshots.getValue("first").accounts.size)
            assertTrue(fixture.remoteSnapshots["second"]?.accounts.isNullOrEmpty())
            assertFailsWith<IllegalStateException> { fixture.connect(email = "third@example.com") }
        } finally { fixture.controller.close() }
    }

    @Test fun remoteErasureFinishesBeforeLocalAccountDataIsPurged() = runTest {
        val fixture = Fixture()
        try {
            val guest = fixture.addGuestAccount()
            fixture.connect(includeLocal = true)
            fixture.controller.eraseAccount("password", "DELETE_MY_ACCOUNT")
            val remoteIndex = fixture.events.indexOf("remote-erase:first")
            val localIndex = fixture.events.indexOf("purge:first")
            assertTrue(remoteIndex >= 0 && localIndex > remoteIndex)
            assertEquals(TreasurySnapshot(), fixture.disk.read("first"))
            assertSame(guest, fixture.controller.repository.value)
            assertEquals(1, guest.snapshot().accounts.size)
        } finally { fixture.controller.close() }
    }

    @Test fun failedRemoteErasureDoesNotPurgeTheLocalAccountOrSignOut() = runTest {
        val fixture = Fixture()
        try {
            fixture.addGuestAccount()
            fixture.connect(includeLocal = true)
            fixture.failRemoteErase = true
            assertFailsWith<IllegalStateException> { fixture.controller.eraseAccount("password", "DELETE_MY_ACCOUNT") }
            assertEquals("first", fixture.controller.repository.value.ownerId)
            assertIs<ConnectionState.SignedIn>(fixture.controller.connection.value)
            assertEquals(1, fixture.disk.read("first").accounts.size)
            assertTrue("purge:first" !in fixture.events)
        } finally { fixture.controller.close() }
    }

    @Test fun failedLogoutStillRemovesTheSessionAndReturnsToGuestData() = runTest {
        val fixture = Fixture()
        try {
            val guest = fixture.addGuestAccount()
            fixture.connect()
            fixture.failLogout = true
            assertFailsWith<IllegalStateException> { fixture.controller.disconnect() }
            assertSame(guest, fixture.controller.repository.value)
            assertIs<ConnectionState.Local>(fixture.controller.connection.value)
            assertTrue(fixture.clients.single().closed)
            assertTrue("close:first" in fixture.events)
        } finally { fixture.controller.close() }
    }

    @Test fun profileIsPublishedOnlyAfterFirstSyncAndCancelledConnectionCleansUp() = runTest {
        val fixture = Fixture()
        try {
            val guest = fixture.addGuestAccount()
            fixture.pullGate = CompletableDeferred()
            val connecting = launch { fixture.connect() }
            fixture.pullEntered.await()
            assertSame(guest, fixture.controller.repository.value)
            assertIs<ConnectionState.Local>(fixture.controller.connection.value)
            connecting.cancelAndJoin()
            assertSame(guest, fixture.controller.repository.value)
            assertTrue(fixture.clients.single().closed)
            assertTrue("close:first" in fixture.events)
        } finally { fixture.controller.close() }
    }

    @Test fun sameOwnerIdOnAnotherServerCannotReadOrUploadTheFirstServersCache() = runTest {
        val fixture = Fixture()
        try {
            fixture.addGuestAccount()
            fixture.connect(includeLocal = true)
            fixture.controller.disconnect()
            fixture.connect(server = "https://another-server.example")
            assertEquals("first", fixture.controller.repository.value.ownerId)
            assertEquals(TreasurySnapshot(), fixture.controller.repository.value.snapshot())
            assertTrue(fixture.clients.last().pushes.isEmpty())
            fixture.controller.disconnect()
            fixture.connect(server = "https://TREASURY.EXAMPLE:443/")
            assertEquals(1, fixture.controller.repository.value.snapshot().accounts.size)
        } finally { fixture.controller.close() }
    }

    @Test fun oauthPendingThenApprovedPublishesOnlyTheVerifiedAccount() = runTest {
        val fixture = Fixture()
        try {
            val guest = fixture.addGuestAccount()
            val challenge = fixture.controller.beginOAuth("https://treasury.example", "google")
            assertEquals("12345678", challenge.verificationCode)
            assertFalse(challenge.toString().contains("private-poll-secret"))
            assertFalse(fixture.controller.pollOAuth(includeLocalData = false))
            assertSame(guest, fixture.controller.repository.value)
            fixture.oauthReady = true
            assertTrue(fixture.controller.pollOAuth(includeLocalData = false))
            assertEquals("first", fixture.controller.repository.value.ownerId)
            assertEquals(TreasurySnapshot(), fixture.controller.repository.value.snapshot())
            assertFalse(fixture.clients.last().closed)
            assertIs<ConnectionState.SignedIn>(fixture.controller.connection.value)
        } finally { fixture.controller.close() }
    }

    @Test fun cancellingOAuthClosesItsClientAndRemovesThePendingSecret() = runTest {
        val fixture = Fixture()
        try {
            val guest = fixture.addGuestAccount()
            fixture.controller.beginOAuth("https://treasury.example", "github")
            fixture.controller.cancelOAuth()
            assertTrue(fixture.clients.single().closed)
            assertFailsWith<IllegalStateException> { fixture.controller.pollOAuth(false) }
            assertSame(guest, fixture.controller.repository.value)
        } finally { fixture.controller.close() }
    }

    @Test fun oauthCannotDirectTheUserToAnUnrelatedAuthorizationHost() = runTest {
        val fixture = Fixture()
        try {
            fixture.oauthAuthorizationUrl = "https://unrelated.example/collect-credentials"
            assertFailsWith<IllegalArgumentException> { fixture.controller.beginOAuth("https://treasury.example", "discord") }
            assertTrue(fixture.clients.single().closed)
            assertFailsWith<IllegalStateException> { fixture.controller.pollOAuth(false) }
        } finally { fixture.controller.close() }
    }

    @Test fun failedOAuthProfileInitializationCleansUpAndKeepsTheGuestProfile() = runTest {
        val fixture = Fixture()
        try {
            val guest = fixture.addGuestAccount()
            fixture.controller.beginOAuth("https://treasury.example", "google")
            fixture.oauthReady = true
            fixture.failAccountStorage = true
            assertFailsWith<IllegalStateException> { fixture.controller.pollOAuth(true) }
            assertSame(guest, fixture.controller.repository.value)
            assertTrue(fixture.clients.single().closed)
            assertFailsWith<IllegalStateException> { fixture.controller.pollOAuth(false) }
        } finally { fixture.controller.close() }
    }

    @Test fun passwordSignInClosesAnyAbandonedOAuthAttempt() = runTest {
        val fixture = Fixture()
        try {
            fixture.controller.beginOAuth("https://treasury.example", "google")
            fixture.connect()
            assertTrue(fixture.clients.first().closed)
            assertFalse(fixture.clients.last().closed)
            assertEquals("first", fixture.controller.repository.value.ownerId)
            assertFailsWith<IllegalStateException> { fixture.controller.pollOAuth(false) }
        } finally { fixture.controller.close() }
    }

    @Test fun providerDiscoveryAndPasswordRecoveryCloseTheirTemporaryClients() = runTest {
        val fixture = Fixture()
        try {
            fixture.addGuestAccount()
            assertEquals(listOf("google", "github", "discord"), fixture.controller.providers("https://treasury.example"))
            assertTrue(fixture.clients.last().closed)
            fixture.controller.requestPasswordReset("https://treasury.example", "first@example.com")
            assertTrue(fixture.clients.last().closed)
            fixture.connect(includeLocal = true)
            fixture.controller.completePasswordReset("https://treasury.example", "reset-token", "new-strong-password")
            assertIs<ConnectionState.Local>(fixture.controller.connection.value)
            assertTrue(fixture.clients.all { it.closed })
            assertEquals(1, fixture.disk.read("first").accounts.size)
            assertTrue("purge:first" !in fixture.events)
        } finally { fixture.controller.close() }
    }
}
