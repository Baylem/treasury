package dev.baylem.treasury.sync

import dev.baylem.treasury.domain.Account
import dev.baylem.treasury.domain.EntityMeta
import dev.baylem.treasury.domain.EntryDirection
import dev.baylem.treasury.domain.FinancialEntry
import dev.baylem.treasury.domain.TreasurySnapshot
import dev.baylem.treasury.repository.LocalRepository
import dev.baylem.treasury.repository.MemoryDataStore
import dev.baylem.treasury.repository.Repository
import dev.baylem.treasury.repository.RepositoryState
import dev.baylem.treasury.repository.entities
import dev.baylem.treasury.repository.mergeSnapshots
import dev.baylem.treasury.repository.treasuryJson
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class RepositorySynchronizerTest {
    private val timestamp = Instant.parse("2026-01-01T00:00:00Z")
    private val day = LocalDate(2026, 1, 1)
    private val session = RemoteSession("secret-token", "user", "2027-01-01T00:00:00Z")
    private fun testUuid(id: String): String = if (id.length == 36) id else "00000000-0000-4000-8000-${id.hashCode().toUInt().toString(16).padStart(12, '0')}"
    private fun meta(id: String, owner: String = "user") = EntityMeta(testUuid(id), owner, timestamp, timestamp)
    private fun account(id: String = "00000000-0000-4000-8000-000000000001", owner: String = "user") = Account(meta(id, owner), "Checking", balanceDate = day)
    private fun entry(id: String = "00000000-0000-4000-8000-000000000002", notes: String = "") = FinancialEntry(meta(id), "00000000-0000-4000-8000-000000000001", "Rent", 100_000,
        EntryDirection.EXPENSE, day, notes = notes)
    private fun repository(owner: String = "user") = LocalRepository(owner, MemoryDataStore(), now = { timestamp })
    private fun Repository.snapshot() = assertIs<RepositoryState.Ready>(state.value).snapshot

    private open inner class FakeRemote : TreasuryRemote {
        var server = TreasurySnapshot()
        val pushes = mutableListOf<TreasurySnapshot>()
        val cursors = mutableListOf<String?>()
        var failNextPush = false
        override suspend fun register(email: String, password: String) = session
        override suspend fun login(email: String, password: String) = session
        override suspend fun me(session: RemoteSession) = RemoteUser(session.ownerId)
        override suspend fun logout(session: RemoteSession) = Unit
        override suspend fun logoutAll(session: RemoteSession) = Unit
        override suspend fun reauthenticate(session: RemoteSession, password: String) = session
        override suspend fun eraseAccount(session: RemoteSession, confirmation: String) = Unit
        override suspend fun requestPasswordReset(email: String) = Unit
        override suspend fun completePasswordReset(token: String, password: String) = Unit
        override suspend fun emailStatus(session: RemoteSession) = EmailVerificationStatus(true, false)
        override suspend fun requestEmailVerification(session: RemoteSession) = Unit
        override suspend fun completeEmailVerification(token: String) = Unit
        override suspend fun providers() = emptyList<String>()
        override suspend fun beginOAuthDevice(provider: String): OAuthDeviceAttempt = error("Not configured")
        override suspend fun pollOAuthDevice(attemptId: String, pollSecret: String): RemoteSession? = null
        override suspend fun pull(session: RemoteSession, cursor: String?, limit: Int): RemoteSyncPage {
            cursors += cursor
            return RemoteSyncPage(server, "2026-01-02T00:00:00Z", false)
        }
        override suspend fun push(session: RemoteSession, snapshot: TreasurySnapshot): RemotePushResult {
            pushes += snapshot
            server = mergeSnapshots(server, snapshot, session.ownerId)
            if (failNextPush) { failNextPush = false; error("Connection lost after server commit") }
            val ids = snapshot.entities().map { it.id }.toSet()
            return RemotePushResult(server.copy(accounts = server.accounts.filter { it.id in ids },
                entries = server.entries.filter { it.id in ids }, plans = server.plans.filter { it.id in ids },
                overrides = server.overrides.filter { it.id in ids }))
        }
        override suspend fun exportAccount(session: RemoteSession) = server
        override fun close() = Unit
    }

    @Test fun downloadsAllPagesBeforeCommittingRelationships() = runTest {
        val remote = object : FakeRemote() {
            override suspend fun pull(session: RemoteSession, cursor: String?, limit: Int): RemoteSyncPage =
                if (cursor == null) RemoteSyncPage(TreasurySnapshot(entries = listOf(entry())), "2026-01-02T00:00:00Z", true)
                else RemoteSyncPage(TreasurySnapshot(accounts = listOf(account())), "2026-01-03T00:00:00Z", false)
        }
        val repository = repository()
        val sync = RepositorySynchronizer(repository, remote, session, now = { timestamp })
        sync.sync()
        assertIs<SyncState.Success>(sync.state.value)
        assertEquals(1, repository.snapshot().entries.size)
        assertEquals(1, repository.snapshot().accounts.size)
        assertTrue(remote.pushes.isEmpty())
    }

    @Test fun interruptedPullDoesNotPartiallyCommitOrAdvanceCursor() = runTest {
        val remote = object : FakeRemote() {
            override suspend fun pull(session: RemoteSession, cursor: String?, limit: Int): RemoteSyncPage {
                cursors += cursor
                if (cursor != null) error("Offline")
                return RemoteSyncPage(TreasurySnapshot(accounts = listOf(account())), "2026-01-02T00:00:00Z", true)
            }
        }
        val repository = repository()
        val sync = RepositorySynchronizer(repository, remote, session, now = { timestamp })
        sync.sync()
        assertIs<SyncState.Failure>(sync.state.value)
        assertEquals(TreasurySnapshot(), repository.snapshot())
        sync.sync()
        assertEquals(listOf(null, "2026-01-02T00:00:00Z", null, "2026-01-02T00:00:00Z"), remote.cursors)
    }

    @Test fun pendingDataUploadsParentsFirstAndSecondSyncDoesNotEchoIt() = runTest {
        val repository = repository()
        repository.initialize()
        repository.saveAccount(account())
        repository.saveEntry(entry())
        val remote = FakeRemote()
        val sync = RepositorySynchronizer(repository, remote, session, now = { timestamp })
        sync.sync()
        assertIs<SyncState.Success>(sync.state.value)
        assertEquals(repository.snapshot(), remote.server)
        assertEquals(1, remote.pushes.size)
        sync.sync()
        assertEquals(1, remote.pushes.size)
    }

    @Test fun lostUploadAcknowledgementIsSafeToRetry() = runTest {
        val repository = repository()
        repository.initialize()
        repository.saveAccount(account())
        val remote = FakeRemote().apply { failNextPush = true }
        val sync = RepositorySynchronizer(repository, remote, session, now = { timestamp })
        sync.sync()
        assertIs<SyncState.Failure>(sync.state.value)
        assertEquals(1, repository.snapshot().accounts.size)
        sync.sync()
        assertIs<SyncState.Success>(sync.state.value)
        assertEquals(1, remote.server.accounts.size)
        assertEquals(1, remote.pushes.size)
    }

    @Test fun newerLocalEditWinsPullAndIsUploaded() = runTest {
        val repository = repository()
        repository.initialize()
        repository.saveAccount(account())
        val old = repository.snapshot()
        repository.saveAccount(old.accounts.single().copy(name = "New local name"))
        val remote = FakeRemote().apply { server = old }
        val sync = RepositorySynchronizer(repository, remote, session, now = { timestamp })
        sync.sync()
        assertIs<SyncState.Success>(sync.state.value)
        assertEquals("New local name", remote.server.accounts.single().name)
    }

    @Test fun editsMadeDuringUploadRemainLocalAndAreSentByTheNextSync() = runTest {
        val repository = repository()
        repository.initialize()
        repository.saveAccount(account())
        var editDuringUpload = true
        val remote = object : FakeRemote() {
            override suspend fun push(session: RemoteSession, snapshot: TreasurySnapshot): RemotePushResult {
                if (editDuringUpload) {
                    editDuringUpload = false
                    repository.saveAccount(repository.snapshot().accounts.single().copy(name = "Edited during upload"))
                }
                return super.push(session, snapshot)
            }
        }
        val sync = RepositorySynchronizer(repository, remote, session, now = { timestamp })
        sync.sync()
        assertTrue(assertIs<SyncState.Success>(sync.state.value).hasPendingChanges)
        assertEquals("Edited during upload", repository.snapshot().accounts.single().name)
        assertEquals("Checking", remote.server.accounts.single().name)
        sync.sync()
        assertFalse(assertIs<SyncState.Success>(sync.state.value).hasPendingChanges)
        assertEquals("Edited during upload", remote.server.accounts.single().name)
    }

    @Test fun editsMadeDuringDownloadSurviveTheRemoteMerge() = runTest {
        val repository = repository()
        repository.initialize()
        repository.saveAccount(account())
        val original = repository.snapshot()
        val remote = object : FakeRemote() {
            override suspend fun pull(session: RemoteSession, cursor: String?, limit: Int): RemoteSyncPage {
                repository.saveAccount(repository.snapshot().accounts.single().copy(name = "Edited during download"))
                return RemoteSyncPage(original, "2026-01-02T00:00:00Z", false)
            }
        }.apply { server = original }
        val sync = RepositorySynchronizer(repository, remote, session, now = { timestamp })
        sync.sync()
        assertIs<SyncState.Success>(sync.state.value)
        assertEquals("Edited during download", repository.snapshot().accounts.single().name)
        assertEquals("Edited during download", remote.server.accounts.single().name)
    }

    @Test fun wrongOwnerAndExpiredSessionCannotSendFinancialData() = runTest {
        assertFailsWith<IllegalArgumentException> {
            RepositorySynchronizer(repository("other"), FakeRemote(), session)
        }
        val remote = FakeRemote()
        val expired = session.copy(expiresAt = "2025-01-01T00:00:00Z")
        val sync = RepositorySynchronizer(repository(), remote, expired, now = { timestamp })
        sync.sync()
        assertIs<SyncState.Failure>(sync.state.value)
        assertTrue(remote.cursors.isEmpty())
        assertTrue(remote.pushes.isEmpty())
    }

    @Test fun serverOwnerLeakIsRejectedWithoutLocalMutation() = runTest {
        val remote = FakeRemote().apply { server = TreasurySnapshot(accounts = listOf(account(owner = "other"))) }
        val repository = repository()
        val sync = RepositorySynchronizer(repository, remote, session, now = { timestamp })
        sync.sync()
        assertIs<SyncState.Failure>(sync.state.value)
        assertEquals(TreasurySnapshot(), repository.snapshot())
    }

    @Test fun batchesRespectRecordLimitsAndUtf8Size() {
        val many = TreasurySnapshot(accounts = listOf(account()), entries = (1..1_001).map { entry("entry-$it") })
        val batches = syncBatches(many)
        assertEquals(3, batches.size)
        assertEquals(1, batches.first().accounts.size)
        assertEquals(1_002, batches.sumOf { it.entities().size })
        assertTrue(batches.all { it.entities().size <= 500 })
        val large = TreasurySnapshot(accounts = listOf(account()), entries = (1..80).map { entry("large-$it", "漢".repeat(9_500)) })
        val sized = syncBatches(large)
        assertTrue(sized.size > 1)
        assertTrue(sized.all { treasuryJson.encodeToString(it).encodeToByteArray().size < 900_000 })
    }

    @Test fun guestMigrationIsExplicitOwnerScopedAndRepeatSafe() = runTest {
        val guest = repository("local")
        guest.initialize()
        guest.saveAccount(account(owner = "local"))
        val signedIn = repository()
        copyLocalDataToAccount(guest, signedIn)
        val copied = signedIn.snapshot()
        assertEquals("user", copied.accounts.single().ownerId)
        assertEquals("local", guest.snapshot().accounts.single().ownerId)
        copyLocalDataToAccount(guest, signedIn)
        assertEquals(copied, signedIn.snapshot())
        assertFailsWith<IllegalArgumentException> { copyLocalDataToAccount(signedIn, repository("other")) }
    }

    @Test fun sessionDebugTextDoesNotExposeBearerToken() {
        assertFalse(session.toString().contains(session.token))
    }
}
