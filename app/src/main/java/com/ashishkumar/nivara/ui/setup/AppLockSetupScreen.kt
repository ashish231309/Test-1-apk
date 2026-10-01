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
import com.ashishkumar.nivara.ui.security.SecureScreenEffect

@Composable
fun AppLockSetupScreen(
    applicationRepository: ApplicationRepository,
    usageAccessRepository: UsageAccessRepository,
    onBack: () -> Unit,
) {
    SecureScreenEffect()
    val factory = remember(applicationRepository, usageAccessRepository) {
        AppLockSetupViewModel.Factory(applicationRepository, usageAccessRepository)
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
            Text("App Lock preparation", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text(
                "This screen checks launchable-app discovery and Usage Access for a future App Lock feature. " +
                    "It does not enable app blocking or read usage history.",
                style = MaterialTheme.typography.bodyMedium,
            )

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
                            "Granted. Nivara can check this capability; Stage 6 does not read usage history.",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        UsageAccessStatus.NOT_GRANTED -> Text(
                            "Not granted. Open Android Settings if you want to prepare this capability.",
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

                    if (current.setup.prerequisitesAvailable) {
                        Text(
                            "These prerequisites are available. App Lock protection is not active in this stage.",
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
