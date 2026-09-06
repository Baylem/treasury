package dev.baylem.treasury.sync

import dev.baylem.treasury.domain.TreasurySnapshot
import dev.baylem.treasury.repository.Repository
import dev.baylem.treasury.repository.RepositoryState
import dev.baylem.treasury.repository.entities
import dev.baylem.treasury.repository.treasuryJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlin.time.Clock
import kotlin.time.Instant

sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Success(val at: Instant, val hasPendingChanges: Boolean = false) : SyncState
    data class Failure(val message: String) : SyncState
}

/**
 * Opt-in sync. Sessions/cursors remain in memory; restarting pulls from the beginning safely.
 * A complete pull is committed before uploads, because paged deltas can omit parent records.
 * Upload retries are idempotent. The server cursor is separate from client LWW timestamps.
 */
class RepositorySynchronizer(
    private val repository: Repository,
    private val remote: TreasuryRemote,
    private val session: RemoteSession,
    private val now: () -> Instant = { Clock.System.now() },
) {
    init { require(repository.ownerId == session.ownerId) { "The repository belongs to a different account." } }
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = mutableState.asStateFlow()
    private var cursor: String? = null
    private val uploaded = mutableMapOf<String, String>()

    suspend fun sync() {
        mutex.withLock {
            mutableState.value = SyncState.Running
            try {
                check(Instant.parse(session.expiresAt) > now()) { "Your session expired. Sign in again." }
                repository.initialize()
                readySnapshot()
                var nextCursor = cursor
                var incoming = TreasurySnapshot()
                var pages = 0
                do {
                    check(++pages <= 1_000) { "The server returned too many sync pages. Try again later." }
                    val page = remote.pull(session, nextCursor)
                    require(page.snapshot.entities().all { it.ownerId == session.ownerId }) { "The server returned data for another owner." }
                    val oldCursor = nextCursor
                    nextCursor = page.cursor
                    if (oldCursor != null) check(Instant.parse(nextCursor) >= Instant.parse(oldCursor)) { "The server returned an invalid sync cursor." }
                    if (page.hasMore) check(nextCursor != oldCursor && page.snapshot.entities().isNotEmpty()) { "The server returned a non-advancing sync page." }
                    incoming = appendLatest(incoming, page.snapshot)
                    check(incoming.entities().size <= 100_000) { "The server returned too many records." }
                } while (page.hasMore)
                repository.merge(incoming)
                cursor = nextCursor
                // Records received from the server need no echo upload unless a local version won.
                uploaded.putAll(encodedRecords(incoming))
                val pending = onlyPending(readySnapshot())
                for (batch in syncBatches(pending)) {
                    val response = remote.push(session, batch)
                    require(response.snapshot.entities().map { it.id }.toSet() == batch.entities().map { it.id }.toSet()) {
                        "The server did not acknowledge every submitted record."
                    }
                    repository.merge(response.snapshot)
                    uploaded.putAll(encodedRecords(response.snapshot))
                }
                mutableState.value = SyncState.Success(now(), onlyPending(readySnapshot()).entities().isNotEmpty())
            } catch (cancelled: CancellationException) {
                mutableState.value = SyncState.Idle
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = SyncState.Failure(failure.message ?: "Unable to sync. Your changes remain saved on this device.")
            }
        }
    }

    private fun readySnapshot() = (repository.state.value as? RepositoryState.Ready)?.snapshot
        ?: error("Local storage is not ready. Your data has not been uploaded.")

    private fun onlyPending(snapshot: TreasurySnapshot): TreasurySnapshot = snapshot.copy(
        accounts = snapshot.accounts.filter { uploaded[it.id] != treasuryJson.encodeToString(it) },
        entries = snapshot.entries.filter { uploaded[it.id] != treasuryJson.encodeToString(it) },
        plans = snapshot.plans.filter { uploaded[it.id] != treasuryJson.encodeToString(it) },
        overrides = snapshot.overrides.filter { uploaded[it.id] != treasuryJson.encodeToString(it) },
    )
}

private fun encodedRecords(snapshot: TreasurySnapshot): Map<String, String> = buildMap {
    snapshot.accounts.forEach { put(it.id, treasuryJson.encodeToString(it)) }
    snapshot.entries.forEach { put(it.id, treasuryJson.encodeToString(it)) }
    snapshot.plans.forEach { put(it.id, treasuryJson.encodeToString(it)) }
    snapshot.overrides.forEach { put(it.id, treasuryJson.encodeToString(it)) }
}

private fun appendLatest(first: TreasurySnapshot, second: TreasurySnapshot): TreasurySnapshot = TreasurySnapshot(
    (first.accounts + second.accounts).associateBy { it.id }.values.toList(),
    (first.entries + second.entries).associateBy { it.id }.values.toList(),
    (first.plans + second.plans).associateBy { it.id }.values.toList(),
    (first.overrides + second.overrides).associateBy { it.id }.values.toList(),
)

/** Parents precede dependents, and both record-count and UTF-8 request-size limits are respected. */
internal fun syncBatches(snapshot: TreasurySnapshot): List<TreasurySnapshot> {
    val records = snapshot.accounts.map { TreasurySnapshot(accounts = listOf(it)) } +
        snapshot.entries.map { TreasurySnapshot(entries = listOf(it)) } +
        snapshot.plans.map { TreasurySnapshot(plans = listOf(it)) } +
        snapshot.overrides.map { TreasurySnapshot(overrides = listOf(it)) }
    val batches = mutableListOf<TreasurySnapshot>()
    var current = TreasurySnapshot()
    var bytes = 100
    var count = 0
    records.forEach { record ->
        val size = treasuryJson.encodeToString(record).encodeToByteArray().size
        require(size < 900_000) { "A record is too large to sync." }
        if (count == 500 || bytes + size > 900_000) {
            batches += current
            current = TreasurySnapshot()
            bytes = 100
            count = 0
        }
        current = appendLatest(current, record)
        bytes += size
        count++
    }
    if (count > 0) batches += current
    return batches
}

/** Explicitly copies guest data into an account. Stable UUIDs make a repeated copy idempotent. */
suspend fun copyLocalDataToAccount(local: Repository, account: Repository) {
    require(local.ownerId == "local" && account.ownerId != "local") { "Only guest data may be copied into a signed-in account." }
    local.initialize()
    account.initialize()
    val source = (local.state.value as? RepositoryState.Ready)?.snapshot ?: error("Guest storage is not ready.")
    account.merge(source.copy(
        accounts = source.accounts.map { it.copy(meta = it.meta.copy(ownerId = account.ownerId)) },
        entries = source.entries.map { it.copy(meta = it.meta.copy(ownerId = account.ownerId)) },
        plans = source.plans.map { it.copy(meta = it.meta.copy(ownerId = account.ownerId)) },
        overrides = source.overrides.map { it.copy(meta = it.meta.copy(ownerId = account.ownerId)) },
    ))
}
