package dev.baylem.treasury.storage

import dev.baylem.treasury.repository.Repository
import dev.baylem.treasury.sync.TreasuryRemote
import dev.baylem.treasury.sync.canonicalServerUrl
import dev.baylem.treasury.repository.treasuryJson
import kotlinx.serialization.encodeToString

/** Factories defer opening the database until Repository.initialize, exposing recoverable failures. */
expect fun createRepository(ownerId: String = "local", serverScope: String? = null): Repository
expect fun createRemote(baseUrl: String): TreasuryRemote

internal fun validatedStorageScope(ownerId: String, serverScope: String?): String? {
    require((ownerId == "local") == (serverScope == null)) { "Signed-in storage requires a server scope; guest storage must remain local." }
    return serverScope?.let(::canonicalServerUrl)
}

/** Collision-free physical partition key; entity metadata still carries the authenticated owner. */
internal fun storagePartition(ownerId: String, serverScope: String?): String =
    if (serverScope == null) ownerId else treasuryJson.encodeToString(listOf(serverScope, ownerId))
