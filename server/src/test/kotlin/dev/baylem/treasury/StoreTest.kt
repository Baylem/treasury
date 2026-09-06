package dev.baylem.treasury

import dev.baylem.treasury.domain.*
import dev.baylem.treasury.server.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.util.UUID
import kotlin.test.*
import kotlin.time.Instant

class StoreTest {
    private val now = 1_788_696_000_000L
    private fun user() = UserRecord(UUID.randomUUID().toString(), "${UUID.randomUUID()}@example.com", "hash", now)
    private fun account(owner: String, time: Long = now) = Account(EntityMeta(UUID.randomUUID().toString(), owner, Instant.fromEpochMilliseconds(time), Instant.fromEpochMilliseconds(time)), "Checking", balanceDate = LocalDate(2026, 9, 1))

    @Test fun staleUpdatesLoseAndTombstonesRemainInDelta() = runBlocking {
        val store = testStore()
        val owner = user().also { assertTrue(store.createUser(it)) }.id
        val first = account(owner)
        store.push(owner, TreasurySnapshot(accounts = listOf(first)), now)
        val initial = store.pull(owner, 0, 500)
        val deleted = first.copy(meta = first.meta.copy(updatedAt = Instant.fromEpochMilliseconds(now + 10), deletedAt = Instant.fromEpochMilliseconds(now + 10), revision = 2))
        store.push(owner, TreasurySnapshot(accounts = listOf(deleted)), now + 10)
        val stale = store.push(owner, TreasurySnapshot(accounts = listOf(first.copy(name = "stale"))), now + 20)
        assertEquals(deleted, stale.snapshot.accounts.single())
        assertEquals(deleted, store.pull(owner, cursorTime(initial.cursor), 500).snapshot.accounts.single())
        assertEquals(deleted, store.export(owner).accounts.single())
    }

    @Test fun lateOfflineCreatesAreNotLostAndPaginationHasNoTimestampTies() = runBlocking {
        val store = testStore()
        val owner = user().also { store.createUser(it) }.id
        store.push(owner, TreasurySnapshot(accounts = listOf(account(owner))), now)
        val oldCursor = store.pull(owner, 0, 500).cursor
        val offline = List(3) { account(owner, now - 100_000) }
        store.push(owner, TreasurySnapshot(accounts = offline), now)
        var cursor = cursorTime(oldCursor)
        val ids = mutableSetOf<String>()
        repeat(3) { index ->
            val page = store.pull(owner, cursor, 1)
            ids += page.snapshot.accounts.single().id
            assertEquals(index < 2, page.hasMore)
            assertTrue(cursorTime(page.cursor) > cursor)
            cursor = cursorTime(page.cursor)
        }
        assertEquals(offline.map { it.id }.toSet(), ids)
        assertTrue(store.pull(owner, cursor, 1).snapshot.accounts.isEmpty())
    }

    @Test fun concurrentPushesSerializeWithoutLosingRows() = runBlocking {
        val store = testStore()
        val owner = user().also { store.createUser(it) }.id
        val accounts = List(12) { account(owner) }
        accounts.map { async { store.push(owner, TreasurySnapshot(accounts = listOf(it)), now) } }.awaitAll()
        assertEquals(accounts.map { it.id }.toSet(), store.export(owner).accounts.map { it.id }.toSet())
    }

    @Test fun invalidGraphsAndMetadataDoNotPartiallyCommit() = runBlocking {
        val store = testStore()
        val owner = user().also { store.createUser(it) }.id
        val good = account(owner)
        val bad = FinancialEntry(good.meta.copy(id = UUID.randomUUID().toString()), "missing", "Invalid", 100, EntryDirection.EXPENSE, LocalDate(2026, 9, 6))
        assertFailsWith<ApiException> { store.push(owner, TreasurySnapshot(accounts = listOf(good), entries = listOf(bad)), now) }
        assertTrue(store.export(owner).accounts.isEmpty())
        store.push(owner, TreasurySnapshot(accounts = listOf(good)), now)
        assertFailsWith<ApiException> { store.push(owner, TreasurySnapshot(accounts = listOf(good.copy(meta = good.meta.copy(createdAt = Instant.fromEpochMilliseconds(now - 1))))), now) }
        assertEquals(good, store.export(owner).accounts.single())
    }

    @Test fun sessionExpiryLimitsRevocationAndAccountErasureAreDurable() = runBlocking {
        val store = testStore()
        val owner = user().also { store.createUser(it) }.id
        val sessions = (0..5).map { SessionRecord(Secrets.hash("session$it"), owner, now + it, now + 100) }
        sessions.forEach { store.addSession(it) }
        assertNull(store.session(sessions.first().hash, now + 6))
        assertNotNull(store.session(sessions.last().hash, now + 6))
        assertNull(store.session(sessions.last().hash, now + 100))
        store.push(owner, TreasurySnapshot(accounts = listOf(account(owner))), now)
        store.eraseAccount(owner)
        assertNull(store.findUser(owner))
        assertNull(store.session(sessions.last().hash, now + 6))
        assertTrue(store.export(owner).accounts.isEmpty())
    }

    @Test fun oauthStateIsOneUseExpiresAndIsProviderBound() = runBlocking {
        val store = testStore()
        val flow = OAuthFlow(Secrets.hash(Secrets.token()), "google", Secrets.token(), System.currentTimeMillis() + 60_000)
        store.addOAuthFlow(flow)
        assertNull(store.consumeOAuthFlow(flow.stateHash, "github", System.currentTimeMillis()))
        assertEquals(flow, store.consumeOAuthFlow(flow.stateHash, "google", System.currentTimeMillis()))
        assertNull(store.consumeOAuthFlow(flow.stateHash, "google", System.currentTimeMillis()))
    }

    @Test fun actualArgon2idHashVerifiesAndSaltsAreUnique() = runBlocking {
        val hasher = Argon2Passwords()
        val first = hasher.hash("a very strong password")
        val second = hasher.hash("a very strong password")
        assertTrue(first.startsWith("\$argon2id\$"))
        assertNotEquals(first, second)
        assertTrue(hasher.verify(first, "a very strong password"))
        assertFalse(hasher.verify(first, "a wrong password"))
    }
}
