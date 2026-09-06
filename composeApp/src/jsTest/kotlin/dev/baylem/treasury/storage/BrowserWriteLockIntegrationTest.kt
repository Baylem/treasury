package dev.baylem.treasury.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.resume
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

class BrowserWriteLockIntegrationTest {
    @Test fun realWebLocksSerializeIndependentWriters() = runTest(timeout = 15.seconds) {
        val name = "treasury-integration-serialize"
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var secondEntered = false
        var writes = 0
        val first = launch {
            withBrowserWriteLock(name) {
                acquired.complete(Unit)
                release.await()
                writes++
            }
        }
        acquired.await()
        val second = launch { withBrowserWriteLock(name) { secondEntered = true; writes++ } }
        nextBrowserTurn()
        assertFalse(secondEntered, "A second browser writer must wait until the first releases its lock.")
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(2, writes)
    }

    @Test fun cancellationReleasesHeldLocksAndAbortsQueuedRequests() = runTest(timeout = 15.seconds) {
        val name = "treasury-integration-cancel"
        val acquired = CompletableDeferred<Unit>()
        val first = launch { withBrowserWriteLock(name) { acquired.complete(Unit); awaitCancellation() } }
        acquired.await()
        var queuedEntered = false
        val queued = launch { withBrowserWriteLock(name) { queuedEntered = true } }
        nextBrowserTurn()
        queued.cancelAndJoin()
        first.cancelAndJoin()
        withBrowserWriteLock(name) { assertFalse(queuedEntered) }
    }

    private suspend fun nextBrowserTurn() = suspendCancellableCoroutine<Unit> { continuation ->
        pauseBrowser { if (continuation.isActive) continuation.resume(Unit) }
    }
}

private fun pauseBrowser(callback: () -> Unit): Unit = js("{ setTimeout(callback, 50); }")
