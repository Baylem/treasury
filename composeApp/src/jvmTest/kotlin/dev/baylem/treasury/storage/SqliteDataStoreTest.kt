package dev.baylem.treasury.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.baylem.treasury.domain.Account
import dev.baylem.treasury.domain.EntityMeta
import dev.baylem.treasury.domain.EntryDirection
import dev.baylem.treasury.domain.FinancialEntry
import dev.baylem.treasury.domain.TreasurySnapshot
import dev.baylem.treasury.repository.EntityKind
import dev.baylem.treasury.repository.LocalRepository
import dev.baylem.treasury.repository.RepositoryState
import dev.baylem.treasury.storage.db.TreasuryDatabase
import java.nio.file.Files
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant

class SqliteDataStoreTest {
    private val timestamp = Instant.parse("2026-01-01T00:00:00Z")
    private val day = LocalDate(2026, 1, 1)
    private fun account(owner: String = "local", name: String = "Checking") = Account(
        EntityMeta("00000000-0000-4000-8000-000000000001", owner, timestamp, timestamp), name, balanceDate = day,
    )

    @Test fun realSqlitePersistsTombstonesAcrossDriverReopening() = runTest {
        val directory = Files.createTempDirectory("treasury-storage-test")
        val file = directory.resolve("treasury.db")
        fun store() = SqliteDataStore({ JdbcSqliteDriver("jdbc:sqlite:$file", Properties(), TreasuryDatabase.Schema) }, Dispatchers.IO)
        try {
            val first = LocalRepository("local", store())
            first.initialize()
            first.saveAccount(account())
            first.saveEntry(FinancialEntry(EntityMeta("00000000-0000-4000-8000-000000000002", "local", timestamp, timestamp), "00000000-0000-4000-8000-000000000001", "Rent",
                90_000, EntryDirection.EXPENSE, day))
            first.softDelete(EntityKind.ENTRY, "00000000-0000-4000-8000-000000000002")
            val expected = assertIs<RepositoryState.Ready>(first.state.value).snapshot
            first.close()
            val reopened = LocalRepository("local", store())
            reopened.initialize()
            val actual = assertIs<RepositoryState.Ready>(reopened.state.value).snapshot
            assertEquals(expected, actual)
            assertNotNull(actual.entries.single().deletedAt)
            reopened.close()
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun sqliteRowsAndErasureAreScopedByOwner() = runTest {
        val store = SqliteDataStore({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TreasuryDatabase.Schema) }, Dispatchers.IO)
        try {
            store.write("local", TreasurySnapshot(accounts = listOf(account())))
            store.write("second", TreasurySnapshot(accounts = listOf(account("second"))))
            store.purgeOwner("local")
            assertEquals(TreasurySnapshot(), store.read("local"))
            assertEquals(1, store.read("second").accounts.size)
        } finally { store.close() }
    }

    @Test fun sqliteRejectsNormalWritesThatWouldDropTombstones() = runTest {
        val store = SqliteDataStore({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TreasuryDatabase.Schema) }, Dispatchers.IO)
        try {
            val deleted = account().copy(meta = account().meta.copy(deletedAt = timestamp))
            store.write("local", TreasurySnapshot(accounts = listOf(deleted)))
            assertFailsWith<IllegalStateException> { store.write("local", TreasurySnapshot()) }
            assertNotNull(store.read("local").accounts.single().deletedAt)
        } finally { store.close() }
    }

    @Test fun externalChangesAreDetectedInsideTheWriteTransaction() = runTest {
        val directory = Files.createTempDirectory("treasury-concurrency-test")
        val file = directory.resolve("treasury.db")
        fun store() = SqliteDataStore({ JdbcSqliteDriver("jdbc:sqlite:$file", Properties(), TreasuryDatabase.Schema) }, Dispatchers.IO)
        val first = store()
        val second = store()
        try {
            first.write("local", TreasurySnapshot(accounts = listOf(account())))
            second.read("local")
            first.write("local", TreasurySnapshot(accounts = listOf(account(name = "First window"))))
            assertFailsWith<IllegalStateException> { second.write("local", TreasurySnapshot(accounts = listOf(account(name = "Stale window")))) }
            assertEquals("First window", second.read("local").accounts.single().name)
        } finally {
            first.close()
            second.close()
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun sqliteRollsBackAllRowsWhenAWriteFailsMidTransaction() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TreasuryDatabase.Schema)
        driver.execute(null, """CREATE TRIGGER reject_bad BEFORE INSERT ON stored_entity
            WHEN NEW.id = '00000000-0000-4000-8000-000000000008' BEGIN SELECT RAISE(ABORT, 'Simulated write failure'); END""", 0)
        val store = SqliteDataStore({ driver }, Dispatchers.IO)
        try {
            val good = account().copy(meta = account().meta.copy(id = "00000000-0000-4000-8000-000000000007"))
            val bad = account().copy(meta = account().meta.copy(id = "00000000-0000-4000-8000-000000000008"))
            assertFailsWith<Exception> { store.write("local", TreasurySnapshot(accounts = listOf(good, bad))) }
            assertEquals(TreasurySnapshot(), store.read("local"))
            store.write("local", TreasurySnapshot(accounts = listOf(good)))
            assertEquals(listOf(good), store.read("local").accounts)
        } finally { store.close() }
    }

    @Test fun serverScopeSeparatesTheSameOwnerAndIdsInOneSqliteFile() = runTest {
        val directory = Files.createTempDirectory("treasury-server-scope-test")
        val file = directory.resolve("treasury.db")
        fun store(scope: String) = SqliteDataStore({ JdbcSqliteDriver("jdbc:sqlite:$file", Properties(), TreasuryDatabase.Schema) }, Dispatchers.IO, scope)
        val first = store("https://first.example")
        val second = store("https://second.example")
        try {
            first.write("same-owner", TreasurySnapshot(accounts = listOf(account("same-owner", "Private first server"))))
            assertEquals(TreasurySnapshot(), second.read("same-owner"))
            second.write("same-owner", TreasurySnapshot(accounts = listOf(account("same-owner", "Other server"))))
            assertEquals("Private first server", first.read("same-owner").accounts.single().name)
            second.purgeOwner("same-owner")
            assertEquals(1, first.read("same-owner").accounts.size)
        } finally {
            first.close()
            second.close()
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}
