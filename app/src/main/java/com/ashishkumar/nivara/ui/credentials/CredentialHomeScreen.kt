package com.ashishkumar.nivara.ui.credentials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ashishkumar.nivara.R
import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticationResult
import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticator
import com.ashishkumar.nivara.domain.biometrics.BiometricAvailability
import com.ashishkumar.nivara.domain.biometrics.BiometricStatus
import com.ashishkumar.nivara.domain.biometrics.BiometricUnavailableReason
import com.ashishkumar.nivara.domain.credentials.CredentialServiceStatus
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialService
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialType
import com.ashishkumar.nivara.domain.security.session.AuthenticationSource
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import com.ashishkumar.nivara.ui.components.NivaraPageHeader
import com.ashishkumar.nivara.ui.components.NivaraSectionHeader
import com.ashishkumar.nivara.ui.components.NivaraSpacing
import com.ashishkumar.nivara.ui.components.NivaraStatusCard
import com.ashishkumar.nivara.ui.components.NivaraStatusTone
import com.ashishkumar.nivara.ui.security.SecureScreenEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CredentialHomeScreen(
    service: PrimaryCredentialService,
    biometricAuthenticator: BiometricAuthenticator,
    sessionManager: SessionManager,
    refreshKey: Int,
    onEnroll: () -> Unit,
    onVerify: (PrimaryCredentialType) -> Unit,
    onChange: (PrimaryCredentialType) -> Unit,
    onEnableBiometric: (PrimaryCredentialType) -> Unit,
    onDisableBiometric: (PrimaryCredentialType) -> Unit,
    onPrepareAppLock: () -> Unit,
    onManageHiddenApplications: () -> Unit,
    onManageVault: () -> Unit,
) {
    SecureScreenEffect()
    var status by remember { mutableStateOf<CredentialServiceStatus?>(null) }
    var biometricStatus by remember { mutableStateOf<BiometricStatus?>(null) }
    var biometricResult by remember { mutableStateOf<BiometricAuthenticationResult?>(null) }
    var sessionNotice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sessionState by sessionManager.sessionState.collectAsState()
    LaunchedEffect(service, biometricAuthenticator, sessionManager, refreshKey) {
        sessionManager.currentState()
        status = service.status()
        biometricStatus = biometricAuthenticator.status()
        biometricResult = null
    }
    LaunchedEffect(sessionState) {
        if (sessionState == SessionState.Unauthenticated) biometricResult = null
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(NivaraSpacing.large),
            horizontalAlignment = Alignment.Start,
        ) {
            NivaraPageHeader(
                title = "Nivara",
                supportingText = stringResource(R.string.home_welcome),
            )
            when (val current = status) {
                null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Checking local security settings…", style = MaterialTheme.typography.bodyMedium)
                }
                CredentialServiceStatus.NotConfigured -> {
                    NivaraStatusCard(
                        title = "Set up your primary credential",
                        message = "Choose a PIN, password, or pattern to protect Nivara's authenticated actions.",
                        tone = NivaraStatusTone.CAUTION,
                    )
                    Button(modifier = Modifier.fillMaxWidth(), onClick = onEnroll) {
                        Text(stringResource(R.string.credential_setup))
                    }
                }
                is CredentialServiceStatus.Configured -> {
                    NivaraStatusCard(
                        title = "Primary credential ready",
                        message = stringResource(R.string.credential_configured, current.type.displayName()),
                        tone = NivaraStatusTone.SUCCESS,
                    )
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { onVerify(current.type) }) {
                        Text(stringResource(R.string.credential_verify))
                    }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { onChange(current.type) }) {
                        Text(stringResource(R.string.credential_change))
                    }
                    SessionControls(
                        state = sessionState,
                        onLockNow = { scope.launch { sessionManager.lockNow() } },
                    )
                    sessionNotice?.let {
                        NivaraStatusCard("Authentication notice", it, NivaraStatusTone.ERROR)
                    }
                    NivaraSectionHeader(
                        title = "Biometric authentication",
                        supportingText = "Optional and always secondary to your primary credential.",
                    )
                    if (biometricStatus == null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("Checking biometric availability…", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        BiometricControls(
                            status = biometricStatus!!,
                            result = biometricResult,
                            busy = busy,
                            onAuthenticate = {
                                if (!busy) scope.launch {
                                    busy = true
                                    biometricResult = null
                                    sessionNotice = null
                                    try {
                                        val completion = sessionManager.authenticateBiometric {
                                            biometricAuthenticator.authenticate()
                                        }
                                        if (completion.outcome == BiometricAuthenticationResult.Authenticated &&
                                            !completion.sessionEstablished
                                        ) {
                                            sessionNotice = "Quick Lock cancelled that authentication request. Authenticate again."
                                        } else {
                                            biometricResult = completion.outcome
                                        }
                                    } catch (failure: CancellationException) {
                                        throw failure
                                    } catch (_: Exception) {
                                        biometricResult = BiometricAuthenticationResult.SystemError
                                    } finally {
                                        busy = false
                                        biometricStatus = biometricAuthenticator.status()
                                    }
                                }
                            },
                            onEnable = { onEnableBiometric(current.type) },
                            onDisable = { onDisableBiometric(current.type) },
                            onPrimaryFallback = { onVerify(current.type) },
                        )
                    }
                }
                CredentialServiceStatus.InvalidConfiguration -> NivaraStatusCard(
                    title = "Credential settings unavailable",
                    message = stringResource(R.string.credential_configuration_unavailable),
                    tone = NivaraStatusTone.ERROR,
                )
            }
            NivaraSectionHeader(
                title = "Privacy tools",
                supportingText = "Manage existing protections and your encrypted vault.",
            )
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onPrepareAppLock) {
                Text("Prepare App Lock")
            }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onManageHiddenApplications) {
                Text("Manage hidden applications")
            }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onManageVault) {
                Text("External encrypted vault")
            }
        }
    }
}

@Composable
private fun SessionControls(
    state: SessionState,
    onLockNow: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (state) {
            SessionState.Unauthenticated -> Text("No authenticated session. Authenticate again before protected actions.")
            is SessionState.Authenticated -> {
                val source = when (state.session.source) {
                    AuthenticationSource.PRIMARY -> "primary credential"
                    AuthenticationSource.BIOMETRIC -> "biometric"
                }
                Text("Authenticated session active via $source.", color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = onLockNow) { Text("Quick Lock now") }
            }
        }
    }
}

@Composable
private fun BiometricControls(
    status: BiometricStatus,
    result: BiometricAuthenticationResult?,
    busy: Boolean,
    onAuthenticate: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onPrimaryFallback: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (status) {
            is BiometricStatus.Disabled -> {
                Text("Biometric authentication is disabled.")
                if (status.availability == BiometricAvailability.AVAILABLE) {
                    Button(onClick = onEnable) { Text("Enable biometrics") }
                } else {
                    Text(availabilityMessage(status.availability), style = MaterialTheme.typography.bodySmall)
                }
            }
            BiometricStatus.Enabled -> {
                Text("Biometric authentication is enabled.")
                Button(enabled = !busy, onClick = onAuthenticate) {
                    Text(if (busy) "Waiting for biometric…" else "Authenticate with biometrics")
                }
                OutlinedButton(enabled = !busy, onClick = onDisable) { Text("Disable biometrics") }
            }
            is BiometricStatus.Invalidated -> {
                Text("Biometric authentication needs setup again. Your primary credential is unchanged.")
                if (status.availability == BiometricAvailability.AVAILABLE) {
                    Button(onClick = onEnable) { Text("Set up biometrics again") }
                } else {
                    Text(availabilityMessage(status.availability), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onDisable) { Text("Disable biometrics") }
            }
            is BiometricStatus.Unavailable -> {
                Text(unavailableMessage(status.reason))
                OutlinedButton(onClick = onDisable) { Text("Disable biometrics") }
            }
            is BiometricStatus.TemporarilyBlocked -> {
                Text(
                    "Biometrics are temporarily limited. Try again in ${((status.retryAfterMillis + 999) / 1_000).coerceAtLeast(1)} seconds.",
                    color = MaterialTheme.colorScheme.error,
                )
                Button(enabled = !busy, onClick = onAuthenticate) { Text("Try biometric authentication") }
                OutlinedButton(onClick = onDisable) { Text("Disable biometrics") }
            }
        }
        result?.let {
            Text(
                authenticationMessage(it),
                color = if (it is BiometricAuthenticationResult.Authenticated) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        if (result != null && result !is BiometricAuthenticationResult.Authenticated) {
            OutlinedButton(enabled = !busy, onClick = onPrimaryFallback) {
                Text("Use PIN, password, or pattern")
            }
        }
        if (result is BiometricAuthenticationResult.Authenticated) {
            Text("Biometric authentication succeeded.", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun CredentialTypeSelectionScreen(
    onBack: () -> Unit,
    onSelected: (PrimaryCredentialType) -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.credential_select_title), style = MaterialTheme.typography.headlineSmall)
            PrimaryCredentialType.entries.forEach { type ->
                Button(modifier = Modifier.padding(top = 12.dp), onClick = { onSelected(type) }) {
                    Text(type.displayName())
                }
            }
            OutlinedButton(modifier = Modifier.padding(top = 20.dp), onClick = onBack) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
fun PrimaryCredentialType.displayName(): String = stringResource(
    when (this) {
        PrimaryCredentialType.PIN -> R.string.credential_type_pin
        PrimaryCredentialType.PASSWORD -> R.string.credential_type_password
        PrimaryCredentialType.PATTERN -> R.string.credential_type_pattern
    },
)

private fun availabilityMessage(availability: BiometricAvailability): String = when (availability) {
    BiometricAvailability.AVAILABLE -> "Biometric hardware is available."
    BiometricAvailability.NO_HARDWARE -> "This device has no supported biometric hardware."
    BiometricAvailability.HARDWARE_UNAVAILABLE -> "Biometric hardware is temporarily unavailable."
    BiometricAvailability.NO_ENROLLED_BIOMETRICS -> "Enroll a strong biometric in Android settings first."
    BiometricAvailability.UNSUPPORTED -> "This device does not support the required biometric configuration."
    BiometricAvailability.SECURITY_UPDATE_REQUIRED -> "Update Android security components before using biometrics."
    BiometricAvailability.UNKNOWN -> "Biometric availability could not be determined."
}

private fun unavailableMessage(reason: BiometricUnavailableReason): String = when (reason) {
    BiometricUnavailableReason.NO_HARDWARE -> "Biometric hardware is unavailable."
    BiometricUnavailableReason.HARDWARE_UNAVAILABLE -> "Biometric hardware is temporarily unavailable."
    BiometricUnavailableReason.NO_ENROLLED_BIOMETRICS -> "No strong biometric is enrolled on this device."
    BiometricUnavailableReason.UNSUPPORTED -> "This device does not support the required biometric configuration."
    BiometricUnavailableReason.SECURITY_UPDATE_REQUIRED -> "Update Android security components before using biometrics."
    BiometricUnavailableReason.PRIMARY_CREDENTIAL_REQUIRED -> "Set up a primary credential before enabling biometrics."
    BiometricUnavailableReason.PRIMARY_CREDENTIAL_UNAVAILABLE -> "The primary credential must be repaired before using biometrics."
    BiometricUnavailableReason.UNKNOWN -> "Biometric authentication is currently unavailable."
}

private fun authenticationMessage(result: BiometricAuthenticationResult): String = when (result) {
    BiometricAuthenticationResult.Authenticated -> "Biometric authentication succeeded."
    BiometricAuthenticationResult.Failed -> "Biometric authentication failed."
    BiometricAuthenticationResult.UserCancelled -> "Biometric authentication was cancelled."
    BiometricAuthenticationResult.PrimaryCredentialRequired -> "Use your primary credential to continue."
    is BiometricAuthenticationResult.TemporarilyBlocked ->
        "Too many biometric failures. Try again in ${((result.retryAfterMillis + 999) / 1_000).coerceAtLeast(1)} seconds."
    BiometricAuthenticationResult.SystemLockedOut -> "Android has temporarily locked biometric authentication. Use your primary credential."
    is BiometricAuthenticationResult.Unavailable -> unavailableMessage(result.reason)
    BiometricAuthenticationResult.Invalidated -> "The biometric key was invalidated. Use your primary credential and set biometrics up again."
    BiometricAuthenticationResult.SystemError -> "Biometric authentication could not be completed."
    BiometricAuthenticationResult.NotEnabled -> "Biometric authentication is not enabled."
    BiometricAuthenticationResult.PersistenceFailure -> "Biometric security state is unavailable."
}
