package dev.baylem.treasury.storage

import dev.baylem.treasury.domain.TreasurySnapshot
import dev.baylem.treasury.repository.DataStore
import dev.baylem.treasury.repository.RepositoryConflictException
import dev.baylem.treasury.repository.treasuryJson
import dev.baylem.treasury.repository.validateSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Browser beta persistence. localStorage.setItem atomically replaces one owner snapshot and throws
 * on quota/access errors. Browser clearing/private mode can remove it: keep exported backups.
 * Desktop and mobile use SQLDelight. The repository interface remains identical on every target.
 */
internal class BrowserDataStore(
    private val get: (String) -> String?,
    private val set: (String, String) -> Unit,
    private val remove: (String) -> Unit,
    private val withWriteLock: suspend (String, suspend () -> Unit) -> Unit,
    private val serverScope: String? = null,
) : DataStore {
    private val loadedValues = mutableMapOf<String, String?>()

    @Serializable
    private data class BrowserDocument(val version: Int = 1, val snapshot: TreasurySnapshot)

    private fun key(ownerId: String) = "treasury.v1.${storagePartition(ownerId, serverScope)}"

    override suspend fun read(ownerId: String): TreasurySnapshot {
        val value = get(key(ownerId))
        val snapshot = if (value == null) TreasurySnapshot() else {
            val document = treasuryJson.decodeFromString<BrowserDocument>(value)
            require(document.version == 1) { "This browser data version is not supported." }
            document.snapshot
        }
        validateSnapshot(snapshot, ownerId)
        loadedValues[ownerId] = value
        return snapshot
    }

    override suspend fun write(ownerId: String, snapshot: TreasurySnapshot) {
        validateSnapshot(snapshot, ownerId)
        val value = treasuryJson.encodeToString(BrowserDocument(snapshot = snapshot))
        withWriteLock(key(ownerId)) {
            if (ownerId in loadedValues && get(key(ownerId)) != loadedValues[ownerId]) {
                throw RepositoryConflictException("Browser data changed in another tab. Reload Treasury before saving.")
            }
            set(key(ownerId), value)
            loadedValues[ownerId] = value
        }
    }

    override suspend fun purgeOwner(ownerId: String) {
        withWriteLock(key(ownerId)) {
            remove(key(ownerId))
            loadedValues[ownerId] = null
        }
    }
}
