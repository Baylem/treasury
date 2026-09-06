package dev.baylem.treasury

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import dev.baylem.treasury.repository.RepositoryState
import dev.baylem.treasury.storage.createRepository
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--verify-installation"))) {
        // Exercises the packaged runtime, native SQLite library, and repository boundary without a UI.
        // Set -Dtreasury.dataDirectory to an isolated directory when used in build verification.
        runBlocking {
            val repository = createRepository()
            try {
                repository.initialize()
                check(repository.state.value is RepositoryState.Ready) { "Treasury storage could not be initialized." }
                println("Treasury installation verified.")
            } finally {
                repository.close()
            }
        }
        return
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Treasury",
            state = rememberWindowState(width = 1360.dp, height = 900.dp),
        ) {
            App()
        }
    }
}
