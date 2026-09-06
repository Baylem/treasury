@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.baylem.treasury.storage

import dev.baylem.treasury.repository.LocalRepository
import dev.baylem.treasury.repository.Repository
import kotlin.js.js
import dev.baylem.treasury.sync.KtorTreasuryRemote
import dev.baylem.treasury.sync.TreasuryRemote

actual fun createRemote(baseUrl: String): TreasuryRemote = KtorTreasuryRemote(baseUrl)

private fun readBrowserStorage(key: String): String? = js("{ try { return localStorage.getItem(key); } catch (e) { return '__TREASURY_STORAGE_UNAVAILABLE__'; } }")
private fun writeBrowserStorage(key: String, value: String): String? = js("{ try { localStorage.setItem(key, value); return null; } catch (e) { return 'Browser storage is unavailable or full. Export a backup before closing this tab.'; } }")
private fun removeBrowserStorage(key: String): String? = js("{ try { localStorage.removeItem(key); return null; } catch (e) { return 'Browser storage could not be cleared.'; } }")

actual fun createRepository(ownerId: String, serverScope: String?): Repository = LocalRepository(ownerId, BrowserDataStore(
    get = { key -> readBrowserStorage(key).also {
        check(it != "__TREASURY_STORAGE_UNAVAILABLE__") { "Browser storage is unavailable. Enable site storage and reload Treasury." }
    } },
    set = { key, value -> writeBrowserStorage(key, value)?.let { error(it) } },
    remove = { key -> removeBrowserStorage(key)?.let { error(it) } },
    withWriteLock = ::withBrowserWriteLock,
    serverScope = validatedStorageScope(ownerId, serverScope),
))
