package dev.baylem.treasury.storage

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dev.baylem.treasury.repository.LocalRepository
import dev.baylem.treasury.repository.Repository
import dev.baylem.treasury.storage.db.TreasuryDatabase
import kotlinx.coroutines.Dispatchers
import dev.baylem.treasury.sync.KtorTreasuryRemote
import dev.baylem.treasury.sync.TreasuryRemote

actual fun createRemote(baseUrl: String): TreasuryRemote = KtorTreasuryRemote(baseUrl)

actual fun createRepository(ownerId: String, serverScope: String?): Repository = LocalRepository(ownerId,
    SqliteDataStore(openDriver = { NativeSqliteDriver(TreasuryDatabase.Schema, "treasury.db") },
        dispatcher = Dispatchers.Default, serverScope = validatedStorageScope(ownerId, serverScope))
)
