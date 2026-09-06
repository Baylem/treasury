package dev.baylem.treasury.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.baylem.treasury.repository.LocalRepository
import dev.baylem.treasury.repository.Repository
import dev.baylem.treasury.storage.db.TreasuryDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import dev.baylem.treasury.sync.KtorTreasuryRemote
import dev.baylem.treasury.sync.TreasuryRemote

actual fun createRemote(baseUrl: String): TreasuryRemote = KtorTreasuryRemote(baseUrl)

actual fun createRepository(ownerId: String, serverScope: String?): Repository = LocalRepository(ownerId,
    SqliteDataStore(openDriver = {
        val overridePath = System.getProperty("treasury.dataDirectory")
        val os = System.getProperty("os.name").lowercase()
        val directory = when {
            overridePath != null -> Path.of(overridePath)
            os.contains("win") -> Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Treasury")
            os.contains("mac") -> Path.of(System.getProperty("user.home"), "Library", "Application Support", "Treasury")
            else -> Path.of(System.getenv("XDG_DATA_HOME") ?: Path.of(System.getProperty("user.home"), ".local", "share").toString(), "treasury")
        }
        Files.createDirectories(directory)
        val properties = Properties().apply {
            setProperty("journal_mode", "WAL")
            setProperty("synchronous", "FULL")
            setProperty("busy_timeout", "5000")
        }
        JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("treasury.db")}", properties, TreasuryDatabase.Schema)
    }, dispatcher = Dispatchers.IO, serverScope = validatedStorageScope(ownerId, serverScope))
)
