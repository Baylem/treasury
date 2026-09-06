package dev.baylem.treasury.application

import dev.baylem.treasury.repository.Repository
import dev.baylem.treasury.repository.RepositoryState
import dev.baylem.treasury.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ConnectionState {
    data object Local : ConnectionState
    data class SignedIn(val server: String, val email: String, val sync: SyncState = SyncState.Idle) : ConnectionState
}

data class OAuthChallenge(val authorizationUrl: String, val verificationCode: String, val expiresAt: String)

/** UI actions expose no HTTP types or session credentials. */
interface ConnectionActions {
    val connection: StateFlow<ConnectionState>
    suspend fun connect(server: String, email: String, password: String, register: Boolean, includeLocalData: Boolean)
    suspend fun sync()
    suspend fun disconnect()
    suspend fun eraseAccount(password: String, confirmation: String)
    suspend fun requestPasswordReset(server: String, email: String)
    suspend fun completePasswordReset(server: String, token: String, password: String)
    suspend fun emailStatus(): EmailVerificationStatus
    suspend fun requestEmailVerification()
    suspend fun completeEmailVerification(token: String)
    suspend fun providers(server: String): List<String>
    suspend fun beginOAuth(server: String, provider: String): OAuthChallenge
    suspend fun pollOAuth(includeLocalData: Boolean): Boolean
    suspend fun cancelOAuth()
}

class TreasuryController(
    private val repositoryFactory: (String, String?) -> Repository,
    private val remoteFactory: (String) -> TreasuryRemote,
) : ConnectionActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val local = repositoryFactory("local", null)
    private val mutableRepository = MutableStateFlow(local)
    val repository: StateFlow<Repository> = mutableRepository.asStateFlow()
    private val mutableConnection = MutableStateFlow<ConnectionState>(ConnectionState.Local)
    override val connection = mutableConnection.asStateFlow()
    private var remote: TreasuryRemote? = null
    private var session: RemoteSession? = null
    private var synchronizer: RepositorySynchronizer? = null
    private var observer: Job? = null

    private data class PendingOAuth(
        val remote: TreasuryRemote,
        val server: String,
        val provider: String,
        val attempt: OAuthDeviceAttempt
    )

    private var pendingOAuth: PendingOAuth? = null

    override suspend fun connect(
        server: String,
        email: String,
        password: String,
        register: Boolean,
        includeLocalData: Boolean
    ) = lock.withLock {
        check(session == null) { "Sign out before connecting another account." }
        pendingOAuth?.remote?.close(); pendingOAuth = null
        val serverAddress = canonicalServerUrl(server)
        val client = remoteFactory(serverAddress)
        try {
            val authenticated =
                if (register) client.register(email.trim(), password) else client.login(email.trim(), password)
            activate(client, authenticated, serverAddress, email.trim(), includeLocalData)
        } catch (failure: Exception) {
            if (session == null) client.close(); throw failure
        }
    }

    private suspend fun activate(
        client: TreasuryRemote,
        authenticated: RemoteSession,
        serverAddress: String,
        displayName: String,
        includeLocalData: Boolean
    ) {
        var target: Repository? = null
        try {
            target = repositoryFactory(authenticated.ownerId, serverAddress)
            target.initialize()
            check(target.state.value is RepositoryState.Ready) { "Your account’s local storage could not be opened." }
            if (includeLocalData) copyLocalDataToAccount(local, target)
            val sync = RepositorySynchronizer(target, client, authenticated)
            // Finish the first sync before publishing a different profile. Publishing destroys
            // the sign-in form and cancels its composition-owned coroutine scope.
            sync.sync()
            remote = client
            session = authenticated
            synchronizer = sync
            mutableRepository.value = target
            mutableConnection.value = ConnectionState.SignedIn(serverAddress, displayName)
            observer = scope.launch {
                sync.state.collect { state ->
                    mutableConnection.update { current ->
                        if (current is ConnectionState.SignedIn && synchronizer === sync) current.copy(
                            sync = state
                        ) else current
                    }
                }
            }
        } catch (failure: Exception) {
            if (session == null) withContext(NonCancellable) { target?.close(); client.close() }
            throw failure
        }
    }

    override suspend fun providers(server: String): List<String> = lock.withLock {
        val client = remoteFactory(canonicalServerUrl(server))
        try {
            client.providers()
        } finally {
            client.close()
        }
    }

    override suspend fun beginOAuth(server: String, provider: String): OAuthChallenge = lock.withLock {
        check(session == null) { "Sign out before connecting another account." }
        pendingOAuth?.remote?.close(); pendingOAuth = null
        val address = canonicalServerUrl(server)
        val client = remoteFactory(address)
        try {
            val attempt = client.beginOAuthDevice(provider)
            require(attempt.authorizationUrl.startsWith("$address/")) { "The server returned an unexpected sign-in address." }
            pendingOAuth = PendingOAuth(client, address, provider, attempt)
            OAuthChallenge(attempt.authorizationUrl, attempt.verificationCode, attempt.expiresAt)
        } catch (failure: Exception) {
            client.close(); throw failure
        }
    }

    override suspend fun pollOAuth(includeLocalData: Boolean): Boolean = lock.withLock {
        val pending = checkNotNull(pendingOAuth) { "Start sign-in again." }
        val authenticated = pending.remote.pollOAuthDevice(pending.attempt.attemptId, pending.attempt.pollSecret)
            ?: return@withLock false
        try {
            activate(
                pending.remote,
                authenticated,
                pending.server,
                "${pending.provider.replaceFirstChar(Char::uppercase)} account",
                includeLocalData
            )
            pendingOAuth = null
            true
        } catch (failure: Exception) {
            pendingOAuth = null; throw failure
        }
    }

    override suspend fun cancelOAuth() = lock.withLock { pendingOAuth?.remote?.close(); pendingOAuth = null }

    override suspend fun sync() = lock.withLock { checkNotNull(synchronizer) { "Sign in to sync." }.sync() }

    override suspend fun requestPasswordReset(server: String, email: String) = lock.withLock {
        val client = remoteFactory(server.trim())
        try {
            client.requestPasswordReset(email.trim())
        } finally {
            client.close()
        }
    }

    override suspend fun completePasswordReset(server: String, token: String, password: String) = lock.withLock {
        val client = remoteFactory(server.trim())
        try {
            client.completePasswordReset(token.trim(), password)
        } finally {
            client.close()
        }
        if (session != null) withContext(NonCancellable) { returnToLocal() }
    }

    override suspend fun emailStatus() = lock.withLock { checkNotNull(remote).emailStatus(checkNotNull(session)) }
    override suspend fun requestEmailVerification() =
        lock.withLock { checkNotNull(remote).requestEmailVerification(checkNotNull(session)) }

    override suspend fun completeEmailVerification(token: String) =
        lock.withLock { checkNotNull(remote).completeEmailVerification(token.trim()) }

    override suspend fun disconnect() = lock.withLock {
        val activeSession = session ?: return@withLock
        try {
            remote?.logout(activeSession)
        } finally {
            withContext(NonCancellable) { returnToLocal() }
        }
    }

    override suspend fun eraseAccount(password: String, confirmation: String) = lock.withLock {
        require(confirmation == "DELETE_MY_ACCOUNT") { "Type DELETE_MY_ACCOUNT to confirm permanent erasure." }
        val client = checkNotNull(remote) { "Sign in first." }
        val fresh =
            if (password.isBlank()) checkNotNull(session) else client.reauthenticate(checkNotNull(session), password)
        client.eraseAccount(fresh, confirmation)
        // Remote erasure completes before the dedicated local hard-purge path is used.
        withContext(NonCancellable) {
            try {
                mutableRepository.value.eraseLocalData()
            } finally {
                returnToLocal()
            }
        }
    }

    private suspend fun returnToLocal() {
        observer?.cancel()
        observer = null
        synchronizer = null
        session = null
        remote?.close()
        remote = null
        val previous = mutableRepository.value
        mutableRepository.value = local
        mutableConnection.value = ConnectionState.Local
        if (previous !== local) previous.close()
    }

    /** A separate lifecycle scope lets pending SQLite transactions close after composition leaves. */
    fun close() {
        scope.launch {
            try {
                lock.withLock { observer?.cancel(); pendingOAuth?.remote?.close(); remote?.close(); mutableRepository.value.close(); if (mutableRepository.value !== local) local.close() }
            } finally {
                scope.cancel()
            }
        }
    }
}
