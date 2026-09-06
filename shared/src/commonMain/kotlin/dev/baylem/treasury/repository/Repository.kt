package dev.baylem.treasury.repository

import dev.baylem.treasury.domain.Account
import dev.baylem.treasury.domain.EntityMeta
import dev.baylem.treasury.domain.FinancialEntry
import dev.baylem.treasury.domain.OccurrenceOverride
import dev.baylem.treasury.domain.PaymentPlan
import dev.baylem.treasury.domain.TreasurySnapshot
import kotlinx.coroutines.flow.StateFlow

/** The only storage dependency needed by a client. All amounts are integer minor units. */
interface Repository {
    val ownerId: String
    val state: StateFlow<RepositoryState>

    /** Loads durable state. Failures are exposed through [state]; safe to call again to retry. */
    suspend fun initialize()
    fun createEntityMeta(): EntityMeta
    suspend fun saveAccount(account: Account)
    suspend fun saveEntry(entry: FinancialEntry)
    suspend fun savePlan(plan: PaymentPlan)
    suspend fun saveOverride(override: OccurrenceOverride)
    /** Deleting an account/entry also tombstones its dependents in the same transaction. */
    suspend fun softDelete(kind: EntityKind, id: String)
    suspend fun merge(incoming: TreasurySnapshot)
    suspend fun exportBackup(): String
    /** Merges a versioned backup. It never drops records or resurrects a newer tombstone. */
    suspend fun importBackup(json: String)
    /** Local permanent erasure. Remote erasure must finish propagating before this is called. */
    suspend fun eraseLocalData()
    /** Releases platform resources after outstanding operations finish. */
    suspend fun close()
}

enum class EntityKind { ACCOUNT, ENTRY, PLAN, OVERRIDE }

sealed interface RepositoryState {
    data object Loading : RepositoryState
    data class Ready(val snapshot: TreasurySnapshot) : RepositoryState
    data class Failure(val message: String) : RepositoryState
}

/** An atomic owner-scoped storage boundary; a failed write must preserve the previous data. */
interface DataStore {
    suspend fun read(ownerId: String): TreasurySnapshot
    /** Upserts the complete owner snapshot, including every tombstone. No hard deletes. */
    suspend fun write(ownerId: String, snapshot: TreasurySnapshot)
    /** Dedicated erasure path, deliberately separate from regular writes and sync. */
    suspend fun purgeOwner(ownerId: String)
    suspend fun close() = Unit
}

class RepositoryConflictException(message: String) : IllegalStateException(message)

/** Useful for previews and tests; production platform factories supply durable adapters. */
class MemoryDataStore : DataStore {
    private val owners = mutableMapOf<String, TreasurySnapshot>()
    override suspend fun read(ownerId: String): TreasurySnapshot = owners[ownerId] ?: TreasurySnapshot()
    override suspend fun write(ownerId: String, snapshot: TreasurySnapshot) {
        validateSnapshot(snapshot, ownerId)
        owners[ownerId] = snapshot
    }
    override suspend fun purgeOwner(ownerId: String) {
        owners.remove(ownerId)
    }
}
