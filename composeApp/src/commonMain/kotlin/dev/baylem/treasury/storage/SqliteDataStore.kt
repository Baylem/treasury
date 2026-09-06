package dev.baylem.treasury.storage

import app.cash.sqldelight.db.SqlDriver
import dev.baylem.treasury.domain.Account
import dev.baylem.treasury.domain.FinancialEntry
import dev.baylem.treasury.domain.OccurrenceOverride
import dev.baylem.treasury.domain.PaymentPlan
import dev.baylem.treasury.domain.SyncMeta
import dev.baylem.treasury.domain.TreasurySnapshot
import dev.baylem.treasury.repository.DataStore
import dev.baylem.treasury.repository.EntityKind
import dev.baylem.treasury.repository.entities
import dev.baylem.treasury.repository.treasuryJson
import dev.baylem.treasury.repository.validateSnapshot
import dev.baylem.treasury.storage.db.TreasuryDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** Typed domain payloads preserve the shared model; SQL columns index sync metadata and owners. */
class SqliteDataStore(
    private val openDriver: () -> SqlDriver,
    private val dispatcher: CoroutineDispatcher,
    private val serverScope: String? = null,
) : DataStore {
    private var driver: SqlDriver? = null
    private var database: TreasuryDatabase? = null
    private val loadedPayloads = mutableMapOf<String, Map<String, String>>()

    private fun database(): TreasuryDatabase = database ?: run {
        val opened = openDriver()
        try {
            TreasuryDatabase(opened).also {
                driver = opened
                database = it
            }
        } catch (failure: Exception) {
            opened.close()
            throw failure
        }
    }

    override suspend fun read(ownerId: String): TreasurySnapshot = withContext(dispatcher) {
        val accounts = mutableListOf<Account>()
        val entries = mutableListOf<FinancialEntry>()
        val plans = mutableListOf<PaymentPlan>()
        val overrides = mutableListOf<OccurrenceOverride>()
        val rows = database().storedEntityQueries.selectOwner(storagePartition(ownerId, serverScope)).executeAsList()
        rows.forEach { row ->
            val entity: SyncMeta = when (EntityKind.valueOf(row.kind)) {
                EntityKind.ACCOUNT -> treasuryJson.decodeFromString<Account>(row.payload).also(accounts::add)
                EntityKind.ENTRY -> treasuryJson.decodeFromString<FinancialEntry>(row.payload).also(entries::add)
                EntityKind.PLAN -> treasuryJson.decodeFromString<PaymentPlan>(row.payload).also(plans::add)
                EntityKind.OVERRIDE -> treasuryJson.decodeFromString<OccurrenceOverride>(row.payload).also(overrides::add)
            }
            check(entity.ownerId == ownerId && entity.id == row.id && entity.revision == row.revision &&
                entity.createdAt.toString() == row.created_at && entity.updatedAt.toString() == row.updated_at &&
                entity.deletedAt?.toString() == row.deleted_at
            ) { "Local storage contains inconsistent metadata. Restore a known-good backup." }
        }
        TreasurySnapshot(accounts, entries, plans, overrides).also {
            validateSnapshot(it, ownerId)
            loadedPayloads[ownerId] = rows.associate { row -> row.id to row.payload }
        }
    }

    override suspend fun write(ownerId: String, snapshot: TreasurySnapshot): Unit = withContext(dispatcher) {
        validateSnapshot(snapshot, ownerId)
        val db = database()
        val savedPayloads = mutableMapOf<String, String>()
        db.transaction {
            val ids = snapshot.entities().map { it.id }.toSet()
            val partition = storagePartition(ownerId, serverScope)
            val stored = db.storedEntityQueries.selectOwner(partition).executeAsList()
            val currentPayloads = stored.associate { it.id to it.payload }
            check(stored.all { it.id in ids } &&
                (ownerId !in loadedPayloads || currentPayloads == loadedPayloads[ownerId])
            ) {
                "Local data changed in another window. Reopen Treasury before saving."
            }
            fun save(entity: SyncMeta, kind: EntityKind, payload: String) {
                savedPayloads[entity.id] = payload
                if (currentPayloads[entity.id] == payload) return
                // UPDATE retains the row; SQLite REPLACE would physically delete/reinsert it.
                db.storedEntityQueries.insertIfAbsent(partition, entity.id, kind.name, entity.createdAt.toString(),
                    entity.updatedAt.toString(), entity.deletedAt?.toString(), entity.revision, payload)
                db.storedEntityQueries.updateEntity(kind.name, entity.createdAt.toString(), entity.updatedAt.toString(),
                    entity.deletedAt?.toString(), entity.revision, payload, partition, entity.id)
            }
            snapshot.accounts.forEach { save(it, EntityKind.ACCOUNT, treasuryJson.encodeToString(it)) }
            snapshot.entries.forEach { save(it, EntityKind.ENTRY, treasuryJson.encodeToString(it)) }
            snapshot.plans.forEach { save(it, EntityKind.PLAN, treasuryJson.encodeToString(it)) }
            snapshot.overrides.forEach { save(it, EntityKind.OVERRIDE, treasuryJson.encodeToString(it)) }
        }
        loadedPayloads[ownerId] = savedPayloads
    }

    override suspend fun purgeOwner(ownerId: String): Unit = withContext(dispatcher) {
        database().transaction { database().storedEntityQueries.purgeOwner(storagePartition(ownerId, serverScope)) }
        loadedPayloads[ownerId] = emptyMap()
    }

    override suspend fun close(): Unit = withContext(dispatcher) {
        driver?.close()
        driver = null
        database = null
        loadedPayloads.clear()
    }
}
