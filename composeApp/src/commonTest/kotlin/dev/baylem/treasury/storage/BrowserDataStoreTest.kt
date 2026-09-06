package dev.baylem.treasury.storage

import dev.baylem.treasury.domain.Account
import dev.baylem.treasury.domain.EntityMeta
import dev.baylem.treasury.domain.TreasurySnapshot
import dev.baylem.treasury.repository.RepositoryConflictException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class BrowserDataStoreTest {
    private val lock = Mutex()
    private suspend fun withTestLock(key: String, operation: suspend () -> Unit) = lock.withLock { operation() }
    private fun snapshot(name: String = "Checking"): TreasurySnapshot {
        val timestamp = Instant.parse("2026-01-01T00:00:00Z")
        return TreasurySnapshot(accounts = listOf(Account(EntityMeta("00000000-0000-4000-8000-000000000001", "local", timestamp, timestamp),
            name, balanceDate = LocalDate(2026, 1, 1))))
    }

    @Test fun browserSnapshotSurvivesReopeningAndErasureIsOwnerScoped() = runTest {
        val storage = mutableMapOf<String, String>()
        fun store() = BrowserDataStore(storage::get, { key, value -> storage[key] = value }, { storage.remove(it) }, ::withTestLock)
        store().write("local", snapshot())
        assertEquals(snapshot(), store().read("local"))
        store().purgeOwner("another-owner")
        assertEquals(snapshot(), store().read("local"))
    }

    @Test fun quotaFailureKeepsTheLastGoodSnapshot() = runTest {
        val storage = mutableMapOf<String, String>()
        var full = false
        val store = BrowserDataStore(storage::get, { key, value ->
            if (full) error("Quota exceeded")
            storage[key] = value
        }, { storage.remove(it) }, ::withTestLock)
        store.write("local", snapshot())
        full = true
        assertFailsWith<IllegalStateException> { store.write("local", snapshot("New name")) }
        assertEquals(snapshot(), store.read("local"))
    }

    @Test fun otherTabChangesAreDetectedBeforeTheyCanBeOverwritten() = runTest {
        val storage = mutableMapOf<String, String>()
        fun store() = BrowserDataStore(storage::get, { key, value -> storage[key] = value }, { storage.remove(it) }, ::withTestLock)
        val first = store()
        val second = store()
        first.write("local", snapshot())
        second.read("local")
        first.write("local", snapshot("Saved in first tab"))
        assertFailsWith<RepositoryConflictException> { second.write("local", snapshot("Stale edit")) }
        assertEquals(snapshot("Saved in first tab"), second.read("local"))
    }

    @Test fun sameOwnerOnDifferentServersUsesSeparateStorageKeys() = runTest {
        val storage = mutableMapOf<String, String>()
        fun store(scope: String) = BrowserDataStore(storage::get, { key, value -> storage[key] = value }, { storage.remove(it) }, ::withTestLock, scope)
        val first = store("https://first.example")
        val second = store("https://second.example")
        first.write("local", snapshot("First server"))
        assertEquals(TreasurySnapshot(), second.read("local"))
        second.write("local", snapshot("Second server"))
        second.purgeOwner("local")
        assertEquals(snapshot("First server"), first.read("local"))
    }

    @Test fun concurrentWritersSerializeTheCompleteCompareAndSaveOperation() = runTest {
        val storage = mutableMapOf<String, String>()
        val requestedLocks = mutableListOf<String>()
        val exclusive = Mutex()
        var insideLock = false
        val guarded: suspend (String, suspend () -> Unit) -> Unit = { key, operation ->
            requestedLocks += key
            exclusive.withLock {
                assertTrue(!insideLock)
                insideLock = true
                try { yield(); operation() } finally { insideLock = false }
            }
        }
        fun store() = BrowserDataStore(storage::get, { key, value ->
            assertTrue(insideLock)
            storage[key] = value
        }, { key -> assertTrue(insideLock); storage.remove(key) }, guarded)
        val first = store()
        val second = store()
        first.read("local")
        second.read("local")
        val outcomes = listOf(async { runCatching { first.write("local", snapshot("First")) } },
            async { runCatching { second.write("local", snapshot("Second")) } }).awaitAll()
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.exceptionOrNull() is RepositoryConflictException })
        first.purgeOwner("local")
        assertEquals(3, requestedLocks.size)
        assertEquals(1, requestedLocks.toSet().size)
    }
}
