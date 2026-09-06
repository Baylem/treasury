package dev.baylem.treasury.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.baylem.treasury.application.ConnectionActions
import dev.baylem.treasury.application.ConnectionState
import dev.baylem.treasury.application.OAuthChallenge
import dev.baylem.treasury.sync.SyncState
import dev.baylem.treasury.sync.EmailVerificationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

internal val LocalConnection = staticCompositionLocalOf<ConnectionActions?> { null }

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ConnectionSettings() {
    val actions = LocalConnection.current ?: return
    val state by actions.connection.collectAsState()
    var server by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords and bearer tokens must never enter saved instance state or backups.
    var password by remember { mutableStateOf("") }
    var register by rememberSaveable { mutableStateOf(false) }
    var includeLocal by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var erase by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf(false) }
    var verification by remember { mutableStateOf<EmailVerificationStatus?>(null) }
    var verificationToken by remember { mutableStateOf("") }
    var verificationSent by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf<List<String>?>(null) }
    var challenge by remember { mutableStateOf<OAuthChallenge?>(null) }
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    fun perform(operation: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try {
                operation()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure; error =
                    failure.message ?: "Could not reach the server. Your local entries remain saved."
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(state is ConnectionState.SignedIn) {
        if (state is ConnectionState.SignedIn) {
            try {
                verification = actions.emailStatus()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure; error = failure.message
            }
        }
    }
    LaunchedEffect(challenge) {
        if (challenge != null) {
            try {
                while (true) {
                    delay(3_000)
                    if (actions.pollOAuth(includeLocal)) {
                        challenge = null; break
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Sign-in expired. Please start again."
                challenge = null
                actions.cancelOAuth()
            }
        }
    }
    Panel(Modifier.fillMaxWidth()) {
        Text("Sync across your devices", style = MaterialTheme.typography.titleLarge)
        when (val current = state) {
            ConnectionState.Local -> {
                Text(
                    "Connect to your Treasury server to keep a signed-in profile in sync. Your local profile remains available when you sign out.",
                    color = Muted
                )
                Field(
                    server,
                    { server = it },
                    "Server address",
                    supporting = "An HTTPS Treasury server, such as https://treasury.example.com"
                )
                ChoiceRow(listOf(false, true), register, { if (it) "Create account" else "Sign in" }, { register = it })
                Field(email, { email = it }, "Email address")
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Use 12 or more characters for a new account.") })
                Row {
                    Checkbox(
                        checked = includeLocal,
                        onCheckedChange = { includeLocal = it }); Text(
                    "Copy this device’s local entries into this account",
                    modifier = Modifier.padding(top = 12.dp).weight(1f)
                )
                }
                Button(
                    enabled = !busy && server.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        perform {
                            actions.connect(server, email, password, register, includeLocal); password = ""
                        }
                    }) { Text(if (busy) "Connecting…" else if (register) "Create account & connect" else "Sign in & connect") }
                TextButton(onClick = { recovery = true }, enabled = !busy) { Text("Forgot password?") }
                HorizontalDivider()
                TextButton(
                    enabled = !busy && server.isNotBlank() && challenge == null,
                    onClick = {
                        perform {
                            providers = actions.providers(server)
                        }
                    }) { Text("Google, GitHub, or Discord sign-in") }
                providers?.let { available ->
                    if (available.isEmpty()) Text(
                        "No social sign-in providers are available on this server.",
                        color = Muted
                    )
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        available.forEach { provider ->
                            OutlinedButton(
                                enabled = !busy && challenge == null,
                                onClick = { perform { challenge = actions.beginOAuth(server, provider) } }) {
                                Text(
                                    when (provider) {
                                        "github" -> "GitHub"; "google" -> "Google"; "discord" -> "Discord"; else -> provider
                                    }
                                )
                            }
                        }
                    }
                }
                challenge?.let { pending ->
                    Text("Enter this code in the sign-in page", style = MaterialTheme.typography.titleMedium)
                    Text(pending.verificationCode, style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "Open the browser, sign in, and enter the code shown here to connect this device. Return here when finished.",
                        color = Muted
                    )
                    Button(onClick = {
                        try {
                            uriHandler.openUri(pending.authorizationUrl)
                        } catch (failure: Exception) {
                            error = failure.message ?: "Could not open the browser."
                        }
                    }) { Text("Open sign-in page") }
                    TextButton(onClick = {
                        perform {
                            actions.cancelOAuth(); challenge = null
                        }
                    }) { Text("Cancel sign-in") }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(
                    "You’ll sign in again after restarting the app. Session tokens are kept in memory only.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            is ConnectionState.SignedIn -> {
                Text(current.email, style = MaterialTheme.typography.titleMedium)
                Text(current.server, color = Muted)
                Text(
                    when (val sync = current.sync) {
                        SyncState.Idle -> "Your changes are saved on this device."; SyncState.Running -> "Syncing…"; is SyncState.Success -> if (sync.hasPendingChanges) "New local changes are ready to sync." else "Last synced ${sync.at}."; is SyncState.Failure -> sync.message
                    }, color = if (current.sync is SyncState.Failure) Expense else Muted
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && current.sync != SyncState.Running,
                        onClick = { perform { actions.sync() } }) { Text("Sync now") }
                    OutlinedButton(enabled = !busy, onClick = { perform { actions.disconnect() } }) { Text("Sign out") }
                }
                Text(
                    "Use Sync now after making changes or before switching devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
                verification?.let { emailState ->
                    if (emailState.verified) Text("Email verified", color = Income)
                    else if (emailState.deliveryAvailable) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                perform {
                                    actions.requestEmailVerification(); verificationSent = true
                                }
                            }) { Text(if (verificationSent) "Resend verification email" else "Verify email address") }
                        if (verificationSent) {
                            Text("Check your email and paste the verification code here.", color = Muted)
                            Field(verificationToken, { verificationToken = it }, "Verification code")
                            Button(
                                enabled = !busy && verificationToken.isNotBlank(),
                                onClick = {
                                    perform {
                                        actions.completeEmailVerification(verificationToken); verificationToken =
                                        ""; verification = actions.emailStatus()
                                    }
                                }) { Text("Verify email") }
                        }
                    }
                }
                TextButton(
                    enabled = !busy,
                    onClick = { erase = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = Expense)
                ) { Text("Permanently delete account") }
            }
        }
        error?.let { Text(it, color = Expense) }
    }
    if (recovery) {
        var resetToken by remember { mutableStateOf("") }
        var newPassword by remember { mutableStateOf("") }
        var sent by remember { mutableStateOf(false) }
        EditorDialog(
            "Reset your password",
            { recovery = false },
            {
                if (resetToken.isBlank()) {
                    actions.requestPasswordReset(server, email)
                    sent = true
                } else {
                    actions.completePasswordReset(server, resetToken, newPassword)
                    password = ""
                }
            },
            if (resetToken.isBlank()) "Send reset code" else "Set new password",
            closeAfterSave = { resetToken.isNotBlank() }) {
            Field(server, { server = it }, "Treasury server address")
            Field(email, { email = it }, "Email address")
            Text(
                if (sent) "If that address belongs to an account, a reset code has been sent. Paste it below." else "Request a reset code, then paste the code from your email and choose a new password.",
                color = Muted
            )
            Field(resetToken, { resetToken = it }, "Reset code from email")
            OutlinedTextField(
                newPassword,
                { newPassword = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("New password · 12–128 characters") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    if (erase) {
        var confirmation by remember { mutableStateOf("") }
        var erasePassword by remember { mutableStateOf("") }
        EditorDialog(
            "Permanently delete account",
            { erase = false },
            { actions.eraseAccount(erasePassword, confirmation) },
            "Delete account"
        ) {
            Text(
                "This permanently erases your server account, all financial records, and this profile’s data on this device. Export a backup first if you need a copy. Other devices must clear their local copies separately.",
                color = Expense
            )
            Field(confirmation, { confirmation = it }, "Type DELETE_MY_ACCOUNT")
            Text(
                "For Google, GitHub, or Discord, sign out and sign in again just before deleting, then leave the password blank.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                erasePassword,
                { erasePassword = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("Confirm password · email accounts") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
