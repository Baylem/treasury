package dev.baylem.treasury.repository

import dev.baylem.treasury.domain.Account
import dev.baylem.treasury.domain.EntityMeta
import dev.baylem.treasury.domain.EntryDirection
import dev.baylem.treasury.domain.FinancialEntry
import dev.baylem.treasury.domain.Frequency
import dev.baylem.treasury.domain.OccurrenceOverride
import dev.baylem.treasury.domain.OverrideAction
import dev.baylem.treasury.domain.PaymentPlan
import dev.baylem.treasury.domain.PlanKind
import dev.baylem.treasury.domain.RecurrenceRule
import dev.baylem.treasury.domain.TreasurySnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class LocalRepositoryTest {
    private val timestamp = Instant.parse("2026-01-01T00:00:00Z")
    private val day = LocalDate(2026, 1, 1)
    private fun testUuid(id: String): String = if (id.length == 36) id else "00000000-0000-4000-8000-${id.hashCode().toUInt().toString(16).padStart(12, '0')}"
    private fun meta(id: String, owner: String = "local") = EntityMeta(testUuid(id), owner, timestamp, timestamp)
    private fun account(id: String = "00000000-0000-4000-8000-000000000001", owner: String = "local") = Account(meta(id, owner), "Checking", balanceDate = day)
    private fun entry(id: String = "00000000-0000-4000-8000-000000000002", recurring: Boolean = false) = FinancialEntry(
        meta(id), "00000000-0000-4000-8000-000000000001", "Rent", 150_000, EntryDirection.EXPENSE, day,
        recurrence = if (recurring) RecurrenceRule(Frequency.MONTHLY) else null,
    )
    private fun repository(store: DataStore = MemoryDataStore()) = LocalRepository("local", store, now = { timestamp })
    private fun Repository.snapshot() = assertIs<RepositoryState.Ready>(state.value).snapshot

    @Test fun newRepositoryLoadsEmptyWithoutSeedingFinancialData() = runTest {
        val repository = repository()
        assertIs<RepositoryState.Loading>(repository.state.value)
        repository.initialize()
        assertEquals(TreasurySnapshot(), repository.snapshot())
    }

    @Test fun saveCommitsDurablyBeforePublishingAndSurvivesReopen() = runTest {
        val store = MemoryDataStore()
        val repository = repository(store)
        repository.initialize()
        repository.saveAccount(account())
        repository.saveEntry(entry())
        val reopened = repository(store)
        reopened.initialize()
        assertEquals(repository.snapshot(), reopened.snapshot())
    }

    @Test fun writesDoNotPublishWhenDurableStorageFails() = runTest {
        val store = object : DataStore {
            override suspend fun read(ownerId: String) = TreasurySnapshot()
            override suspend fun write(ownerId: String, snapshot: TreasurySnapshot) { error("Disk full") }
            override suspend fun purgeOwner(ownerId: String) = Unit
        }
        val repository = repository(store)
        repository.initialize()
        assertFailsWith<IllegalStateException> { repository.saveAccount(account()) }
        assertEquals(TreasurySnapshot(), repository.snapshot())
    }

    @Test fun loadFailureIsVisibleAndCanBeRetriedWithoutOverwritingData() = runTest {
        var fail = true
        val store = object : DataStore {
            override suspend fun read(ownerId: String): TreasurySnapshot {
                if (fail) error("Unable to read database")
                return TreasurySnapshot(accounts = listOf(account()))
            }
            override suspend fun write(ownerId: String, snapshot: TreasurySnapshot) = Unit
            override suspend fun purgeOwner(ownerId: String) = Unit
        }
        val repository = repository(store)
        repository.initialize()
        assertIs<RepositoryState.Failure>(repository.state.value)
        fail = false
        repository.initialize()
        assertEquals(1, repository.snapshot().accounts.size)
    }

    @Test fun updatesAdvanceTimestampEvenIfClockDoesNotAdvance() = runTest {
        val repository = repository()
        repository.initialize()
        repository.saveAccount(account())
        val saved = repository.snapshot().accounts.single()
        repository.saveAccount(saved.copy(name = "New name"))
        val updated = repository.snapshot().accounts.single()
        assertEquals(2L, updated.revision)
        assertEquals(saved.createdAt, updated.createdAt)
        assertTrue(updated.updatedAt > saved.updatedAt)
    }

    @Test fun staleFormsCannotOverwriteACommittedEdit() = runTest {
        val repository = repository()
        repository.initialize()
        repository.saveAccount(account())
        val stale = repository.snapshot().accounts.single()
        repository.saveAccount(stale.copy(name = "First edit"))
        assertFailsWith<RepositoryConflictException> { repository.saveAccount(stale.copy(name = "Stale edit")) }
        assertEquals("First edit", repository.snapshot().accounts.single().name)
    }

    @Test fun concurrentIndependentEditsDoNotLoseRecords() = runTest {
        val repository = repository()
        repository.initialize()
        (1..20).map { index -> async { repository.saveAccount(account("account-$index")) } }.awaitAll()
        assertEquals(20, repository.snapshot().accounts.size)
    }

    @Test fun foreignOwnerAndBrokenReferencesAreRejectedAtomically() = runTest {
        val repository = repository()
        repository.initialize()
        assertFailsWith<IllegalArgumentException> { repository.saveAccount(account(owner = "someone-else")) }
        assertFailsWith<IllegalArgumentException> { repository.saveEntry(entry()) }
        assertEquals(TreasurySnapshot(), repository.snapshot())
    }

    @Test fun ownersShareStorageWithoutSharingRows() = runTest {
        val store = MemoryDataStore()
        val first = repository(store)
        val second = LocalRepository("second", store)
        first.initialize()
        second.initialize()
        first.saveAccount(account())
        second.saveAccount(account(owner = "second"))
        first.eraseLocalData()
        assertEquals(1, store.read("second").accounts.size)
        assertEquals(TreasurySnapshot(), store.read("local"))
    }

    @Test fun deletingAnAccountAtomicallyTombstonesEveryDependent() = runTest {
        val repository = repository()
        repository.initialize()
        repository.saveAccount(account())
        repository.saveEntry(entry(recurring = true))
        repository.savePlan(PaymentPlan(meta("00000000-0000-4000-8000-000000000003"), "00000000-0000-4000-8000-000000000001", "Laptop", 80_000, day, 4, PlanKind.BNPL))
        repository.saveOverride(OccurrenceOverride(meta("00000000-0000-4000-8000-000000000004"), "00000000-0000-4000-8000-000000000002", day, OverrideAction.Skip))
        repository.softDelete(EntityKind.ACCOUNT, "00000000-0000-4000-8000-000000000001")
        assertEquals(4, repository.snapshot().entities().size)
        repository.snapshot().entities().forEach { assertNotNull(it.deletedAt) }
        val before = repository.snapshot()
        repository.softDelete(EntityKind.ACCOUNT, "00000000-0000-4000-8000-000000000001")
        assertEquals(before, repository.snapshot())
    }

    @Test fun replacingAnOccurrenceRetainsTheSupersededOverrideTombstone() = runTest {
        val repository = repository()
        repository.initialize()
        repository.saveAccount(account())
        repository.saveEntry(entry(recurring = true))
        repository.saveOverride(OccurrenceOverride(meta("00000000-0000-4000-8000-000000000005"), "00000000-0000-4000-8000-000000000002", day, OverrideAction.Skip))
        repository.saveOverride(OccurrenceOverride(meta("00000000-0000-4000-8000-000000000006"), "00000000-0000-4000-8000-000000000002", day, OverrideAction.Replace(day, 200_000)))
        assertNotNull(repository.snapshot().overrides.single { it.id == "00000000-0000-4000-8000-000000000005" }.deletedAt)
        assertNull(repository.snapshot().overrides.single { it.id == "00000000-0000-4000-8000-000000000006" }.deletedAt)
    }

    @Test fun backupRoundTripPreservesTombstonesAndOlderImportCannotResurrectThem() = runTest {
        val repository = repository()
        repository.initialize()
        repository.saveAccount(account())
        repository.saveEntry(entry())
        val olderBackup = repository.exportBackup()
        repository.softDelete(EntityKind.ENTRY, "00000000-0000-4000-8000-000000000002")
        val backup = repository.exportBackup()
        val restored = repository()
        restored.initialize()
        restored.importBackup(backup)
        restored.importBackup(olderBackup)
        assertEquals(repository.snapshot(), restored.snapshot())
        assertNotNull(restored.snapshot().entries.single().deletedAt)
    }

    @Test fun malformedOrFutureBackupLeavesExistingDataUntouched() = runTest {
        val repository = repository()
        repository.initialize()
        repository.saveAccount(account())
        val before = repository.snapshot()
        assertFailsWith<IllegalArgumentException> { repository.importBackup("not json") }
        val future = TreasuryBackup(version = 2, ownerId = "local", exportedAt = timestamp, snapshot = before)
        assertFailsWith<IllegalArgumentException> { repository.importBackup(treasuryJson.encodeToString(future)) }
        val broken = TreasuryBackup(ownerId = "local", exportedAt = timestamp, snapshot = TreasurySnapshot(entries = listOf(entry())))
        assertFailsWith<IllegalArgumentException> { repository.importBackup(treasuryJson.encodeToString(broken)) }
        assertEquals(before, repository.snapshot())
    }

    @Test fun backupWithNonUuidIdsCannotIntroduceUnsyncableRecords() = runTest {
        val repository = repository()
        repository.initialize()
        val malformed = account().copy(meta = account().meta.copy(id = "not-a-uuid"))
        val backup = TreasuryBackup(ownerId = "local", exportedAt = timestamp,
            snapshot = TreasurySnapshot(accounts = listOf(malformed)))
        assertFailsWith<IllegalArgumentException> { repository.importBackup(treasuryJson.encodeToString(backup)) }
        assertEquals(TreasurySnapshot(), repository.snapshot())
    }

    @Test fun equalTimestampConflictsConvergeInBothMergeDirections() {
        val first = TreasurySnapshot(accounts = listOf(account().copy(name = "A")))
        val second = TreasurySnapshot(accounts = listOf(account().copy(name = "Z")))
        assertEquals(mergeSnapshots(first, second, "local"), mergeSnapshots(second, first, "local"))
        assertEquals(mergeSnapshots(first, second, "local"), mergeSnapshots(mergeSnapshots(first, second, "local"), first, "local"))
    }

    @Test fun equalTimestampAndRevisionDeletionWinsOverActivePayload() {
        val live = account().copy(name = "Z")
        val deleted = account().copy(meta = meta("00000000-0000-4000-8000-000000000001").copy(deletedAt = timestamp), name = "A")
        val merged = mergeSnapshots(TreasurySnapshot(accounts = listOf(live)), TreasurySnapshot(accounts = listOf(deleted)), "local")
        assertNotNull(merged.accounts.single().deletedAt)
    }

    @Test fun mergeCannotChangeAnExistingRecordsCreationTimestamp() {
        val first = TreasurySnapshot(accounts = listOf(account()))
        val later = Instant.parse("2026-01-02T00:00:00Z")
        val invalid = account().copy(meta = meta("00000000-0000-4000-8000-000000000001").copy(createdAt = later, updatedAt = later))
        assertFailsWith<IllegalArgumentException> { mergeSnapshots(first, TreasurySnapshot(accounts = listOf(invalid)), "local") }
    }

    @Test fun deltasMayReferenceExistingParentsButCannotReuseIdsAcrossKinds() {
        val base = TreasurySnapshot(accounts = listOf(account()))
        assertEquals(1, mergeSnapshots(base, TreasurySnapshot(entries = listOf(entry())), "local").entries.size)
        assertFailsWith<IllegalArgumentException> {
            mergeSnapshots(base, TreasurySnapshot(entries = listOf(entry("00000000-0000-4000-8000-000000000001"))), "local")
        }
    }

    @Test fun deletedRecordsCannotBeEditedAndClosePreventsReuse() = runTest {
        val repository = repository()
        repository.initialize()
        repository.saveAccount(account())
        repository.softDelete(EntityKind.ACCOUNT, "00000000-0000-4000-8000-000000000001")
        assertFailsWith<IllegalArgumentException> { repository.saveAccount(repository.snapshot().accounts.single().copy(name = "No")) }
        repository.close()
        assertFailsWith<IllegalStateException> { repository.initialize() }
        assertFailsWith<IllegalStateException> { repository.saveAccount(account()) }
    }
}
