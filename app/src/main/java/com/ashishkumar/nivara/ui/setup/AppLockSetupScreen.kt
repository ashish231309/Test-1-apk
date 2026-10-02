package com.ashishkumar.nivara.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ashishkumar.nivara.domain.app.ApplicationRepository
import com.ashishkumar.nivara.domain.permissions.UsageAccessRepository
import com.ashishkumar.nivara.domain.permissions.UsageAccessSettingsResult
import com.ashishkumar.nivara.domain.permissions.UsageAccessStatus
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityRepository
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityStatus
import com.ashishkumar.nivara.domain.applock.OverlaySettingsResult
import com.ashishkumar.nivara.ui.components.NivaraPageHeader
import com.ashishkumar.nivara.ui.security.SecureScreenEffect

@Composable
fun AppLockSetupScreen(
    applicationRepository: ApplicationRepository,
    usageAccessRepository: UsageAccessRepository,
    overlayCapabilityRepository: OverlayCapabilityRepository,
    onBack: () -> Unit,
    onManageProtectedApps: () -> Unit,
) {
    SecureScreenEffect()
    val factory = remember(applicationRepository, usageAccessRepository, overlayCapabilityRepository) {
        AppLockSetupViewModel.Factory(
            applicationRepository,
            usageAccessRepository,
            overlayCapabilityRepository,
        )
    }
    val viewModel: AppLockSetupViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NivaraPageHeader(
                title = "App Lock preparation",
                supportingText = "Review required Android capabilities and manage the apps covered by your existing App Lock configuration.",
            )
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text(
                "Usage Access lets Nivara detect protected apps. Overlay access lets it display the App Lock surface. " +
                    "Protection is unreliable until both permissions are available. Saved protected choices are not cleared when a prerequisite is missing.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onManageProtectedApps) { Text("Manage protected applications") }

            when (val current = state) {
                AppLockSetupUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text("Checking device capabilities…")
                }
                AppLockSetupUiState.Error -> {
                    Text(
                        "Setup status could not be loaded. Try again.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = viewModel::refresh) { Text("Retry") }
                }
                is AppLockSetupUiState.Ready -> {
                    Text("Launchable apps", style = MaterialTheme.typography.titleMedium)
                    when (current.setup.applicationDiscovery) {
                        com.ashishkumar.nivara.domain.setup.AppLockSetupState.ApplicationDiscovery.AVAILABLE -> {
                            Text("${current.applications.size} apps discovered on this device.")
                            if (current.applications.isEmpty()) {
                                Text("No launchable apps were found.", style = MaterialTheme.typography.bodySmall)
                            } else {
                                current.applications.take(MAX_VISIBLE_APPS).forEach { application ->
                                    Text("• ${application.label}", style = MaterialTheme.typography.bodySmall)
                                }
                                val remaining = current.applications.size - MAX_VISIBLE_APPS
                                if (remaining > 0) {
                                    Text("and $remaining more", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        com.ashishkumar.nivara.domain.setup.AppLockSetupState.ApplicationDiscovery.UNAVAILABLE -> {
                            Text(
                                "App discovery is unavailable right now.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    Text("Usage Access", style = MaterialTheme.typography.titleMedium)
                    when (current.setup.usageAccess) {
                        UsageAccessStatus.GRANTED -> Text(
                            "Granted. Nivara can detect foreground transitions; it does not save usage history.",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        UsageAccessStatus.NOT_GRANTED -> Text(
                            "Not granted. Detection cannot operate reliably until you enable Usage Access.",
                        )
                        UsageAccessStatus.UNAVAILABLE -> Text(
                            "Usage Access status is unavailable on this device.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(onClick = viewModel::openUsageAccessSettings) {
                        Text("Open Usage Access Settings")
                    }
                    when (current.settingsLaunchResult) {
                        UsageAccessSettingsResult.OPENED -> Text(
                            "Settings was opened. Nivara will re-check the status when you return.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        UsageAccessSettingsResult.UNAVAILABLE -> Text(
                            "Android Settings could not be opened from this device.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        null -> Unit
                    }

                    Text("Overlay access", style = MaterialTheme.typography.titleMedium)
                    when (current.overlayCapability) {
                        OverlayCapabilityStatus.GRANTED -> Text(
                            "Granted. Nivara can display its secure App Lock surface.",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        OverlayCapabilityStatus.NOT_GRANTED -> Text(
                            "Not granted. App Lock cannot present its protection surface until enabled.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        OverlayCapabilityStatus.UNAVAILABLE -> Text(
                            "Overlay capability is unsupported or unavailable on this device.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(onClick = viewModel::openOverlayPermissionSettings) {
                        Text("Open Overlay Permission Settings")
                    }
                    when (current.overlaySettingsLaunchResult) {
                        OverlaySettingsResult.OPENED -> Text(
                            "Settings was opened. Nivara will re-check the permission when you return.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OverlaySettingsResult.FAILED -> Text(
                            "Android could not open the overlay-permission page.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        null -> Unit
                    }

                    if (current.setup.prerequisitesAvailable &&
                        current.overlayCapability == OverlayCapabilityStatus.GRANTED
                    ) {
                        Text(
                            "These prerequisites are available. App Lock uses the existing SessionManager session after authentication.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            "Nivara cannot protect apps reliably until discovery, Usage Access, and overlay access are available.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedButton(onClick = viewModel::refresh) { Text("Refresh status") }
                }
            }
        }
    }
}

private const val MAX_VISIBLE_APPS = 12
