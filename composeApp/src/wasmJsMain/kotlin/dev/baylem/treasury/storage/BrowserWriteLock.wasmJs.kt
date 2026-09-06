@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.baylem.treasury.storage

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.js.js

@OptIn(ExperimentalUuidApi::class)
internal suspend fun withBrowserWriteLock(name: String, operation: suspend () -> Unit) {
    val requestId = Uuid.random().toString()
    try {
        suspendCancellableCoroutine<Unit> { continuation ->
            requestBrowserLock(name, requestId,
                acquired = { if (continuation.isActive) continuation.resume(Unit) else releaseBrowserLock(requestId) },
                failed = { message -> if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) },
            )
            continuation.invokeOnCancellation { releaseBrowserLock(requestId) }
        }
        operation()
    } finally {
        releaseBrowserLock(requestId)
    }
}

private fun requestBrowserLock(name: String, requestId: String, acquired: () -> Unit, failed: (String) -> Unit): Unit =
    js("""{
    if (!globalThis.navigator || !navigator.locks) {
        failed('This browser cannot safely save shared site data. Open Treasury in a browser with Web Locks support over HTTPS.');
        return;
    }
    const key = Symbol.for('treasury.writeLocks');
    const pending = globalThis[key] || (globalThis[key] = new Map());
    const controller = new AbortController();
    const entry = { controller, release: null };
    pending.set(requestId, entry);
    try {
        navigator.locks.request(name, { mode: 'exclusive', signal: controller.signal }, () =>
            new Promise(resolve => { entry.release = resolve; acquired(); })
        ).catch(() => failed('Unable to acquire browser storage access. Try saving again.'))
         .finally(() => pending.delete(requestId));
    } catch (error) {
        pending.delete(requestId);
        failed('Unable to acquire browser storage access. Try saving again.');
    }
}""")

private fun releaseBrowserLock(requestId: String): Unit =
    js("""{
    const pending = globalThis[Symbol.for('treasury.writeLocks')];
    const entry = pending && pending.get(requestId);
    if (entry) {
        if (entry.release) entry.release();
        else entry.controller.abort();
    }
}""")
