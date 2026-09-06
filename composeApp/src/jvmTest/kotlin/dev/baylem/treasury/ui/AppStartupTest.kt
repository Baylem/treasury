package dev.baylem.treasury.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.baylem.treasury.App
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Starts App itself: Koin -> controller -> JVM factory -> a real isolated SQLite file. */
@OptIn(ExperimentalTestApi::class)
class AppStartupTest {
    @Test fun appCreatesDurableAccountAndReopensAfterLifecycleDisposal() {
        val fixtures = Path.of("build", "startup-test-data").toAbsolutePath().normalize()
        Files.createDirectories(fixtures)
        val directory = Files.createTempDirectory(fixtures, "app-")
        val previous = System.getProperty("treasury.dataDirectory")
        System.setProperty("treasury.dataDirectory", directory.toString())
        try {
            val visible = mutableStateOf(true)
            runDesktopComposeUiTest(1360, 900, testTimeout = 45.seconds) {
                setContent { if (visible.value) App() }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Create your first account").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Create your first account").performClick()
                onNodeWithText("Account name").performTextReplacement("Startup savings")
                onNodeWithText("Opening balance").performTextReplacement("9876.54")
                onNodeWithText("Save").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Startup savings · USD").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(Files.exists(directory.resolve("treasury.db")))
                runOnIdle { visible.value = false }
                waitForIdle()
                // The last SQLite connection checkpoints and removes its WAL on close.
                waitUntil(timeoutMillis = 10_000) { !Files.exists(directory.resolve("treasury.db-wal")) }
            }

            val reopened = mutableStateOf(true)
            runDesktopComposeUiTest(1360, 900, testTimeout = 45.seconds) {
                setContent { if (reopened.value) App() }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Startup savings · USD").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Accounts", substring = false).performClick()
                onNodeWithText("Opening balance: $9,876.54", substring = true).assertExists()
                onNodeWithText("Edit account").assertExists()
                runOnIdle { reopened.value = false }
                waitForIdle()
                waitUntil(timeoutMillis = 10_000) { !Files.exists(directory.resolve("treasury.db-wal")) }
            }
        } finally {
            if (previous == null) System.clearProperty("treasury.dataDirectory")
            else System.setProperty("treasury.dataDirectory", previous)
        }
    }
}
