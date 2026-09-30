package com.ashishkumar.nivara.ui.credentials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CredentialHomeScreen(
    service: PrimaryCredentialService,
    biometricAuthenticator: BiometricAuthenticator,
    refreshKey: Int,
    onEnroll: () -> Unit,
    onVerify: (PrimaryCredentialType) -> Unit,
    onChange: (PrimaryCredentialType) -> Unit,
    onEnableBiometric: (PrimaryCredentialType) -> Unit,
    onDisableBiometric: (PrimaryCredentialType) -> Unit,
) {
    var status by remember { mutableStateOf<CredentialServiceStatus?>(null) }
    var biometricStatus by remember { mutableStateOf<BiometricStatus?>(null) }
    var biometricResult by remember { mutableStateOf<BiometricAuthenticationResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(service, biometricAuthenticator, refreshKey) {
        status = service.status()
        biometricStatus = biometricAuthenticator.status()
        biometricResult = null
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Nivara", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                text = stringResource(R.string.home_welcome),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            when (val current = status) {
                null -> CircularProgressIndicator()
                CredentialServiceStatus.NotConfigured -> Button(onClick = onEnroll) {
                    Text(stringResource(R.string.credential_setup))
                }
                is CredentialServiceStatus.Configured -> {
                    Text(stringResource(R.string.credential_configured, current.type.displayName()))
                    Button(modifier = Modifier.padding(top = 12.dp), onClick = { onVerify(current.type) }) {
                        Text(stringResource(R.string.credential_verify))
                    }
                    OutlinedButton(modifier = Modifier.padding(top = 8.dp), onClick = { onChange(current.type) }) {
                        Text(stringResource(R.string.credential_change))
                    }
                    if (biometricStatus == null) {
                        CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                    } else {
                        BiometricControls(
                            status = biometricStatus!!,
                            result = biometricResult,
                            busy = busy,
                            onAuthenticate = {
                                if (!busy) scope.launch {
                                    busy = true
                                    biometricResult = null
                                    try {
                                        biometricResult = biometricAuthenticator.authenticate()
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
                CredentialServiceStatus.InvalidConfiguration -> Text(
                    stringResource(R.string.credential_configuration_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
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
