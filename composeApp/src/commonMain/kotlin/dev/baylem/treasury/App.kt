package dev.baylem.treasury

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.baylem.treasury.engine.CalendarEngine
import dev.baylem.treasury.engine.DefaultCalendarEngine
import dev.baylem.treasury.repository.Repository
import dev.baylem.treasury.repository.RepositoryState
import dev.baylem.treasury.storage.createRepository
import dev.baylem.treasury.storage.createRemote
import dev.baylem.treasury.application.TreasuryController
import dev.baylem.treasury.ui.LocalConnection
import dev.baylem.treasury.ui.TreasuryHome
import dev.baylem.treasury.ui.TreasuryTheme
import kotlinx.coroutines.launch
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** UI depends on repository and engine interfaces, never a database or HTTP client. */
@Composable
fun App() {
    val dependencies = remember {
        koinApplication {
            modules(module {
                single {
                    TreasuryController(
                        { owner, server -> createRepository(owner, server) },
                        { url -> createRemote(url) })
                }
                single<CalendarEngine> { DefaultCalendarEngine() }
            })
        }
    }
    val controller = remember { dependencies.koin.get<TreasuryController>() }
    val repository by controller.repository.collectAsState()
    val engine = remember { dependencies.koin.get<CalendarEngine>() }
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) { repository.initialize() }
    DisposableEffect(dependencies) { onDispose { controller.close(); dependencies.close() } }
    TreasuryTheme {
        CompositionLocalProvider(LocalConnection provides controller) {
            key(repository) {
                when (val current = state) {
                    RepositoryState.Loading -> Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }

                    is RepositoryState.Failure -> Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("Your data couldn’t be opened", style = MaterialTheme.typography.headlineSmall)
                            Text(current.message)
                            Button(onClick = { scope.launch { repository.initialize() } }) { Text("Try again") }
                        }
                    }

                    is RepositoryState.Ready -> TreasuryHome(repository, engine, current.snapshot)
                }
            }
        }
    }
}
