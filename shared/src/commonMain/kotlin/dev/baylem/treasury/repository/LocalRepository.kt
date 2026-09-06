package dev.baylem.treasury.repository

import dev.baylem.treasury.domain.Account
import dev.baylem.treasury.domain.EntityMeta
import dev.baylem.treasury.domain.FinancialEntry
import dev.baylem.treasury.domain.OccurrenceOverride
import dev.baylem.treasury.domain.PaymentPlan
import dev.baylem.treasury.domain.SyncMeta
import dev.baylem.treasury.domain.TreasurySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class TreasuryBackup(
    val format: String = "treasury-backup",
    val version: Int = 1,
    val ownerId: String,
    val exportedAt: Instant,
    val snapshot: TreasurySnapshot,
)

/** Publishes state only after durable commit; serializes concurrent edits and detects stale forms. */
@OptIn(ExperimentalUuidApi::class)
class LocalRepository(
    override val ownerId: String,
    private val store: DataStore,
    private val now: () -> Instant = { Clock.System.now() },
    private val newId: () -> String = { Uuid.random().toString() },
) : Repository {
    init { require(ownerId.isNotBlank()) { "Owner is required." } }

    private val mutex = Mutex()
    private var closed = false
    private val mutableState = MutableStateFlow<RepositoryState>(RepositoryState.Loading)
    override val state: StateFlow<RepositoryState> = mutableState.asStateFlow()

    override suspend fun initialize() {
        mutex.withLock {
            check(!closed) { "This repository has been closed." }
            if (mutableState.value is RepositoryState.Ready) return
            mutableState.value = RepositoryState.Loading
            try {
                val loaded = store.read(ownerId)
                withContext(Dispatchers.Default) { validateSnapshot(loaded, ownerId) }
                mutableState.value = RepositoryState.Ready(loaded)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = RepositoryState.Failure(failure.message ?: "Unable to open local storage.")
            }
        }
    }

    override fun createEntityMeta(): EntityMeta {
        val instant = now()
        return EntityMeta(id = newId(), ownerId = ownerId, createdAt = instant, updatedAt = instant)
    }

    override suspend fun saveAccount(account: Account) = mutate { snapshot ->
        val previous = snapshot.accounts.find { it.id == account.id }
        val saved = account.copy(meta = metadataForSave(account, previous))
        snapshot.copy(accounts = snapshot.accounts.replace(saved))
    }

    override suspend fun saveEntry(entry: FinancialEntry) = mutate { snapshot ->
        require(snapshot.accounts.any { it.id == entry.accountId && it.deletedAt == null }) { "Choose an active account." }
        val previous = snapshot.entries.find { it.id == entry.id }
        val saved = entry.copy(meta = metadataForSave(entry, previous))
        require(entry.recurrence != null || snapshot.overrides.none { it.entryId == entry.id && it.deletedAt == null }) {
            "Remove individual occurrence changes before removing the recurrence rule."
        }
        snapshot.copy(entries = snapshot.entries.replace(saved))
    }

    override suspend fun savePlan(plan: PaymentPlan) = mutate { snapshot ->
        require(snapshot.accounts.any { it.id == plan.accountId && it.deletedAt == null }) { "Choose an active account." }
        val previous = snapshot.plans.find { it.id == plan.id }
        snapshot.copy(plans = snapshot.plans.replace(plan.copy(meta = metadataForSave(plan, previous))))
    }

    override suspend fun saveOverride(override: OccurrenceOverride) = mutate { snapshot ->
        require(snapshot.entries.any { it.id == override.entryId && it.deletedAt == null && it.recurrence != null }) {
            "Choose an active recurring entry."
        }
        val previous = snapshot.overrides.find { it.id == override.id }
        val saved = override.copy(meta = metadataForSave(override, previous))
        // Each occurrence has one current override; old ones remain as tombstones for sync.
        val superseded = snapshot.overrides.map { existing ->
            if (existing.id != saved.id && existing.entryId == saved.entryId &&
                existing.originalDate == saved.originalDate && existing.deletedAt == null
            ) existing.copy(meta = tombstone(existing)) else existing
        }
        snapshot.copy(overrides = superseded.replace(saved))
    }

    override suspend fun softDelete(kind: EntityKind, id: String) = mutate { snapshot ->
        val deletedAccounts = if (kind == EntityKind.ACCOUNT) setOf(id) else emptySet()
        val deletedEntries = snapshot.entries.filter {
            (kind == EntityKind.ENTRY && it.id == id) || it.accountId in deletedAccounts
        }.map { it.id }.toSet()
        val exists = when (kind) {
            EntityKind.ACCOUNT -> snapshot.accounts.any { it.id == id }
            EntityKind.ENTRY -> snapshot.entries.any { it.id == id }
            EntityKind.PLAN -> snapshot.plans.any { it.id == id }
            EntityKind.OVERRIDE -> snapshot.overrides.any { it.id == id }
        }
        require(exists) { "The record no longer exists." }
        snapshot.copy(
            accounts = snapshot.accounts.map { if (it.id in deletedAccounts) it.copy(meta = tombstone(it)) else it },
            entries = snapshot.entries.map { if (it.id in deletedEntries) it.copy(meta = tombstone(it)) else it },
            plans = snapshot.plans.map {
                if ((kind == EntityKind.PLAN && it.id == id) || it.accountId in deletedAccounts) it.copy(meta = tombstone(it)) else it
            },
            overrides = snapshot.overrides.map {
                if ((kind == EntityKind.OVERRIDE && it.id == id) || it.entryId in deletedEntries) it.copy(meta = tombstone(it)) else it
            },
        )
    }

    override suspend fun merge(incoming: TreasurySnapshot) = mutate { mergeSnapshots(it, incoming, ownerId) }

    override suspend fun exportBackup(): String {
        val backup = mutex.withLock {
            TreasuryBackup(ownerId = ownerId, exportedAt = now(), snapshot = readySnapshot())
        }
        return withContext(Dispatchers.Default) { treasuryJson.encodeToString(backup) }
    }

    override suspend fun importBackup(json: String) {
        require(json.length <= 20_000_000) { "Backup exceeds the 20 MB import limit." }
        val backup = withContext(Dispatchers.Default) {
            try {
                treasuryJson.decodeFromString<TreasuryBackup>(json)
            } catch (failure: Exception) {
                throw IllegalArgumentException("This is not a valid Treasury backup.", failure)
            }
        }
        require(backup.format == "treasury-backup" && backup.version == 1) { "This backup format is not supported." }
        require(backup.ownerId == ownerId) { "This backup belongs to a different owner." }
        withContext(Dispatchers.Default) { validateSnapshot(backup.snapshot, ownerId) }
        merge(backup.snapshot)
    }

    override suspend fun eraseLocalData() {
        mutex.withLock {
            readySnapshot()
            store.purgeOwner(ownerId)
            mutableState.value = RepositoryState.Ready(TreasurySnapshot())
        }
    }

    override suspend fun close() {
        mutex.withLock {
            if (closed) return
            store.close()
            closed = true
            mutableState.value = RepositoryState.Loading
        }
    }

    private suspend fun mutate(transform: (TreasurySnapshot) -> TreasurySnapshot) {
        mutex.withLock {
            val before = readySnapshot()
            val candidate = withContext(Dispatchers.Default) {
                transform(before).also { validateSnapshot(it, ownerId) }
            }
            if (candidate == before) return
            store.write(ownerId, candidate)
            mutableState.value = RepositoryState.Ready(candidate)
        }
    }

    private fun readySnapshot(): TreasurySnapshot =
        (mutableState.value as? RepositoryState.Ready)?.snapshot
            ?: throw IllegalStateException("Local storage is not ready. Try reopening it.")

    private fun metadataForSave(incoming: SyncMeta, previous: SyncMeta?): EntityMeta {
        require(incoming.ownerId == ownerId) { "This record belongs to a different owner." }
        require(incoming.deletedAt == null) { "A deleted record cannot be edited." }
        if (previous == null) {
            require(incoming.revision == 1L) { "A new record must begin at revision 1." }
            val timestamp = now()
            return incoming.meta.copy(createdAt = timestamp, updatedAt = timestamp, revision = 1)
        }
        require(previous.deletedAt == null) { "A deleted record cannot be edited." }
        if (previous.revision != incoming.revision || previous.updatedAt != incoming.updatedAt) {
            throw RepositoryConflictException("This record changed while you were editing it. Reopen it and try again.")
        }
        require(previous.revision < Long.MAX_VALUE) { "Record revision limit reached." }
        return previous.meta.copy(updatedAt = nextTimestamp(previous), revision = previous.revision + 1)
    }

    private fun tombstone(entity: SyncMeta): EntityMeta {
        if (entity.deletedAt != null) return entity.meta
        require(entity.revision < Long.MAX_VALUE) { "Record revision limit reached." }
        val timestamp = nextTimestamp(entity)
        return entity.meta.copy(updatedAt = timestamp, deletedAt = timestamp, revision = entity.revision + 1)
    }

    private fun nextTimestamp(entity: SyncMeta): Instant = maxOf(now(), entity.updatedAt + 1.nanoseconds)

    private fun <T : SyncMeta> List<T>.replace(entity: T): List<T> =
        (filterNot { it.id == entity.id } + entity).sortedBy { it.id }
}
