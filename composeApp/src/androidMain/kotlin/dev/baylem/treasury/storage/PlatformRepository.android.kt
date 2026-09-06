package dev.baylem.treasury.storage

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.baylem.treasury.repository.LocalRepository
import dev.baylem.treasury.repository.Repository
import dev.baylem.treasury.storage.db.TreasuryDatabase
import kotlinx.coroutines.Dispatchers
import dev.baylem.treasury.sync.KtorTreasuryRemote
import dev.baylem.treasury.sync.TreasuryRemote

actual fun createRemote(baseUrl: String): TreasuryRemote = KtorTreasuryRemote(baseUrl)

private var applicationContext: Context? = null

/** Call from the Android entry point with applicationContext, before composing the app. */
fun initializeStorage(context: Context) {
    applicationContext = context.applicationContext
}

actual fun createRepository(ownerId: String, serverScope: String?): Repository = LocalRepository(ownerId,
    SqliteDataStore(openDriver = {
        AndroidSqliteDriver(TreasuryDatabase.Schema,
            checkNotNull(applicationContext) { "Android storage has not been initialized." }, "treasury.db")
    }, dispatcher = Dispatchers.IO, serverScope = validatedStorageScope(ownerId, serverScope))
)
