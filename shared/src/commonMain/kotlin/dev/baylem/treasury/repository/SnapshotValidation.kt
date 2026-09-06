package dev.baylem.treasury.repository

import dev.baylem.treasury.domain.SyncMeta
import dev.baylem.treasury.domain.TreasurySnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Strict decoding keeps corrupt or future-format backups from silently losing fields. */
val treasuryJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    isLenient = false
    allowStructuredMapKeys = false
}

fun TreasurySnapshot.entities(): List<SyncMeta> = accounts + entries + plans + overrides

private val canonicalUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** Validate the entire graph at trust boundaries, before any durable mutation. */
fun validateSnapshot(snapshot: TreasurySnapshot, ownerId: String) {
    require(ownerId.isNotBlank()) { "Owner is required." }
    val entities = snapshot.entities()
    require(entities.size <= 100_000) { "A snapshot may contain at most 100,000 records." }
    require(entities.map { it.id }.toSet().size == entities.size) { "Duplicate record IDs are not allowed." }
    entities.forEach { entity ->
        require(canonicalUuid.matches(entity.id)) { "Record IDs must be canonical UUIDs." }
        require(entity.ownerId == ownerId) { "This data belongs to a different owner." }
        require(entity.revision >= 1) { "Record revisions must be positive." }
        require(entity.updatedAt >= entity.createdAt) { "A record cannot be updated before it was created." }
        entity.deletedAt?.let { deletedAt ->
            require(deletedAt >= entity.createdAt && deletedAt <= entity.updatedAt) { "Invalid deletion timestamp." }
        }
    }
    val accounts = snapshot.accounts.associateBy { it.id }
    val entries = snapshot.entries.associateBy { it.id }
    snapshot.entries.forEach { entry ->
        require(entry.accountId in accounts) { "Entry '${entry.title}' references a missing account." }
    }
    snapshot.plans.forEach { plan ->
        require(plan.accountId in accounts) { "Plan '${plan.title}' references a missing account." }
    }
    snapshot.overrides.forEach { override ->
        require(override.entryId in entries) { "An occurrence change references a missing entry." }
        require(override.deletedAt != null || entries.getValue(override.entryId).recurrence != null) { "Occurrence changes require a recurring entry." }
    }
}

/**
 * LWW merge, retaining tombstones. Equal timestamp/revision conflicts converge identically on
 * every platform: deletion wins, then the canonical serialized payload breaks the tie.
 * Validation occurs after the merge because a delta can legitimately omit referenced parents.
 */
fun mergeSnapshots(local: TreasurySnapshot, incoming: TreasurySnapshot, ownerId: String): TreasurySnapshot {
    require(incoming.entities().all { it.ownerId == ownerId }) { "This data belongs to a different owner." }
    require(incoming.entities().map { it.id }.toSet().size == incoming.entities().size) { "Duplicate record IDs are not allowed." }
    fun <T : SyncMeta> mergeRows(existing: List<T>, changes: List<T>, encode: (T) -> String): List<T> {
        val result = existing.associateBy { it.id }.toMutableMap()
        changes.forEach { change ->
            val previous = result[change.id]
            require(previous == null || previous.createdAt == change.createdAt) { "A record's creation timestamp is immutable." }
            if (previous == null || compareValuesBy(change, previous,
                    { it.updatedAt }, { it.revision }, { it.deletedAt != null }, { encode(it) }) > 0
            ) result[change.id] = change
        }
        return result.values.sortedBy { it.id }
    }
    return TreasurySnapshot(
        accounts = mergeRows(local.accounts, incoming.accounts) { treasuryJson.encodeToString(it) },
        entries = mergeRows(local.entries, incoming.entries) { treasuryJson.encodeToString(it) },
        plans = mergeRows(local.plans, incoming.plans) { treasuryJson.encodeToString(it) },
        overrides = mergeRows(local.overrides, incoming.overrides) { treasuryJson.encodeToString(it) },
    ).also { validateSnapshot(it, ownerId) }
}
