package com.ashishkumar.nivara.ui.applock

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ashishkumar.nivara.data.app.AndroidApplicationIconProvider
import com.ashishkumar.nivara.domain.app.ApplicationDiscoveryResult
import com.ashishkumar.nivara.domain.app.ApplicationRepository
import com.ashishkumar.nivara.domain.app.InstalledApplication
import com.ashishkumar.nivara.domain.applock.AppLockMonitor
import com.ashishkumar.nivara.domain.applock.AppLockMonitoringController
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityRepository
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationRepository
import com.ashishkumar.nivara.domain.credentials.CredentialServiceStatus
import com.ashishkumar.nivara.domain.permissions.UsageAccessRepository
import com.ashishkumar.nivara.domain.permissions.UsageAccessStatus
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import com.ashishkumar.nivara.ui.security.SecureScreenEffect

@Composable
fun AppLockManagementScreen(
    applicationRepository: ApplicationRepository,
    protectedApplicationRepository: ProtectedApplicationRepository,
    usageAccessRepository: UsageAccessRepository,
    overlayCapabilityRepository: OverlayCapabilityRepository,
    primaryCredentialService: com.ashishkumar.nivara.domain.credentials.PrimaryCredentialService,
    sessionManager: SessionManager,
    appLockMonitor: AppLockMonitor,
    appLockMonitoringController: AppLockMonitoringController,
    applicationIconProvider: AndroidApplicationIconProvider,
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
    onReturnHomeForAuthentication: () -> Unit,
) {
    SecureScreenEffect()
    val lifecycleOwner = LocalLifecycleOwner.current
    val factory = remember(
        applicationRepository,
        protectedApplicationRepository,
        usageAccessRepository,
        overlayCapabilityRepository,
        primaryCredentialService,
        sessionManager,
        appLockMonitor,
        appLockMonitoringController,
        lifecycleOwner,
    ) {
        AppLockManagementViewModel.Factory(
            applicationRepository,
            protectedApplicationRepository,
            usageAccessRepository,
            overlayCapabilityRepository,
            primaryCredentialService,
            sessionManager,
            appLockMonitor,
            requestMonitoringStart = {
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    appLockMonitoringController.start()
                }
            },
        )
    }
    val viewModel: AppLockManagementViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("App Lock", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Choose which launchable apps Nivara should protect. This list changes the same protected set used by App Lock detection.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onBack) { Text("Back") }
                        OutlinedButton(onClick = onOpenSetup) { Text("Permissions and setup") }
                    }
                }
            }

            when (val current = state) {
                AppLockManagementUiState.Loading -> item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Discovering apps and checking protection state…")
                }
                is AppLockManagementUiState.Ready -> managementContent(
                    content = current.content,
                    viewModel = viewModel,
                    iconProvider = applicationIconProvider,
                    onReturnHomeForAuthentication = onReturnHomeForAuthentication,
                    onOpenSetup = onOpenSetup,
                )
                is AppLockManagementUiState.Empty -> managementContent(
                    content = current.content,
                    viewModel = viewModel,
                    iconProvider = applicationIconProvider,
                    onReturnHomeForAuthentication = onReturnHomeForAuthentication,
                    onOpenSetup = onOpenSetup,
                    emptyState = "No launchable applications were discovered. Refresh after installing or enabling apps.",
                )
                is AppLockManagementUiState.NoResults -> managementContent(
                    content = current.content,
                    viewModel = viewModel,
                    iconProvider = applicationIconProvider,
                    onReturnHomeForAuthentication = onReturnHomeForAuthentication,
                    onOpenSetup = onOpenSetup,
                    emptyState = "No applications match this search and section.",
                )
                is AppLockManagementUiState.Unavailable -> managementContent(
                    content = current.content,
                    viewModel = viewModel,
                    iconProvider = applicationIconProvider,
                    onReturnHomeForAuthentication = onReturnHomeForAuthentication,
                    onOpenSetup = onOpenSetup,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.managementContent(
    content: AppLockManagementContent,
    viewModel: AppLockManagementViewModel,
    iconProvider: AndroidApplicationIconProvider,
    onReturnHomeForAuthentication: () -> Unit,
    onOpenSetup: () -> Unit,
    emptyState: String? = null,
) {
    item(key = "readiness") { ReadinessCard(content, onOpenSetup) }
    item(key = "session") { SessionAccessCard(content, onReturnHomeForAuthentication) }

    content.notice?.let { notice ->
        item(key = "notice") { ManagementNoticeCard(notice) }
    }

    if (content.protectedApplications == com.ashishkumar.nivara.domain.applock.ProtectedApplicationsSnapshot.Unavailable) {
        item(key = "protected-store-error") {
            Text(
                "Protected-app storage is unavailable. No apps are being treated as unprotected, and changes are disabled to preserve recoverable configuration.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = viewModel::refresh) { Text("Retry protected-app storage") }
        }
    }
    if (content.discovery == ApplicationDiscoveryResult.Unavailable) {
        item(key = "discovery-error") {
            Text(
                "Installed-app discovery is unavailable. The saved protected set has not been cleared. Refresh to try again.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item(key = "refresh-discovery") {
            OutlinedButton(onClick = viewModel::refresh) { Text("Refresh app discovery") }
        }
        return
    }

    item(key = "search") {
        OutlinedTextField(
            value = content.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search applications") },
            singleLine = true,
            supportingText = { Text("Search uses the existing case-insensitive label and package matching.") },
        )
    }
    item(key = "filters-and-sort") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Show applications", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = content.section == AppLockApplicationSection.ALL,
                    onClick = { viewModel.setSection(AppLockApplicationSection.ALL) },
                    label = { Text("All apps") },
                )
                FilterChip(
                    selected = content.section == AppLockApplicationSection.PROTECTED,
                    onClick = { viewModel.setSection(AppLockApplicationSection.PROTECTED) },
                    label = { Text("Protected") },
                )
            }
            SortSelector(content.sort, viewModel::setSort)
        }
    }

    if (content.visibleApplications.isEmpty() && emptyState == null) {
        item(key = "empty-filter") {
            val storageUnavailable = content.protectedApplications ==
                com.ashishkumar.nivara.domain.applock.ProtectedApplicationsSnapshot.Unavailable
            Text(
                when {
                    storageUnavailable && content.section == AppLockApplicationSection.PROTECTED ->
                        "Protected applications cannot be listed until protected-app storage is available."
                    content.section == AppLockApplicationSection.PROTECTED && content.unavailableProtectedPackageNames.isNotEmpty() ->
                        "No currently discovered protected apps match this view. Saved entries not in the launcher list appear below."
                    content.section == AppLockApplicationSection.PROTECTED ->
                        "No discovered applications are currently protected."
                    else -> "No applications to show."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else if (emptyState != null && content.visibleApplications.isEmpty()) {
        item(key = "empty-state") {
            Text(emptyState, style = MaterialTheme.typography.bodyMedium)
            if (content.discovery is ApplicationDiscoveryResult.Available &&
                content.discovery.applications.isEmpty()
            ) {
                OutlinedButton(onClick = viewModel::refresh) { Text("Refresh app list") }
            }
        }
    }

    val protectedPackages = (content.protectedApplications as? com.ashishkumar.nivara.domain.applock.ProtectedApplicationsSnapshot.Available)
        ?.applications?.mapTo(HashSet()) { it.packageName }
    items(content.visibleApplications, key = { it.packageName }) { application ->
        val protected = protectedPackages?.contains(application.packageName)
        ApplicationRow(
            application = application,
            protected = protected,
            pending = application.packageName in content.pendingPackageNames,
            authorized = content.sessionState is SessionState.Authenticated,
            storageAvailable = protectedPackages != null,
            iconProvider = iconProvider,
            onProtectionChange = { desired -> viewModel.setProtection(application, desired) },
        )
    }

    if (content.section == AppLockApplicationSection.ALL && content.unavailableProtectedPackageNames.isNotEmpty()) {
        item(key = "stale-protected-hint") {
            Text(
                "Some saved protected entries are not in the current launcher list. Select Protected to review or remove them.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (content.section == AppLockApplicationSection.PROTECTED && content.unavailableProtectedPackageNames.isNotEmpty()) {
        item(key = "stale-protected-heading") {
            Text("Protected entries not currently discovered", style = MaterialTheme.typography.titleMedium)
            Text(
                "These saved entries were not in the latest launcher snapshot. They remain protected in configuration until you remove them here.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        itemsIndexed(content.unavailableProtectedPackageNames, key = { _, packageName -> "unavailable-protected-$packageName" }) { index, packageName ->
            UnavailableProtectedRow(
                entryNumber = index + 1,
                pending = packageName in content.pendingPackageNames,
                authorized = content.sessionState is SessionState.Authenticated,
                storageAvailable = protectedPackages != null,
                onUnprotect = { viewModel.unprotectUnavailable(packageName) },
            )
        }
    }

    item(key = "reset-caveat") {
        Text(
            "Clearing Nivara's app data or uninstalling it removes the saved protected-app configuration.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadinessCard(content: AppLockManagementContent, onOpenSetup: () -> Unit) {
    val readiness = content.readiness
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Protection readiness", style = MaterialTheme.typography.titleMedium)
            ReadinessLine("App discovery", readiness.discovery.name.lowercase().replace('_', ' '))
            ReadinessLine("Protected-set storage", readiness.protectedSet.name.lowercase().replace('_', ' '))
            ReadinessLine("Usage Access", usageAccessText(readiness.usageAccess))
            ReadinessLine("Overlay surface", overlayText(readiness.overlay))
            ReadinessLine("Primary credential", credentialText(readiness.primaryCredential))
            ReadinessLine("Foreground monitor", monitoringText(content.monitoringStatus))
            val ready = readiness.discovery == AppLockManagementReadiness.DiscoveryReadiness.AVAILABLE &&
                readiness.protectedSet == AppLockManagementReadiness.ProtectedSetReadiness.AVAILABLE &&
                readiness.usageAccess == UsageAccessStatus.GRANTED &&
                readiness.overlay == com.ashishkumar.nivara.domain.applock.OverlayCapabilityStatus.GRANTED &&
                readiness.primaryCredential is CredentialServiceStatus.Configured
            Text(
                if (ready) {
                    "Prerequisites are currently available. This is not proof that Android has presented an overlay; runtime protection remains best-effort."
                } else {
                    "Protection may be unavailable until each listed prerequisite is restored. Saved protected choices are not silently cleared."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onOpenSetup) { Text("Review permission setup") }
        }
    }
}

@Composable
private fun ReadinessLine(label: String, state: String) {
    Text("$label: $state", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SessionAccessCard(content: AppLockManagementContent, onReturnHome: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (content.sessionState) {
                is SessionState.Authenticated -> Text(
                    "Authenticated Nivara session active. App Lock changes use this existing session and do not extend its timeout.",
                    color = MaterialTheme.colorScheme.primary,
                )
                SessionState.Unauthenticated -> {
                    Text("Verify your primary credential from Nivara Home before changing protected apps.")
                    val label = if (content.readiness.primaryCredential == CredentialServiceStatus.NotConfigured) {
                        "Return Home to set up a primary credential"
                    } else "Return Home to verify your credential"
                    OutlinedButton(onClick = onReturnHome) { Text(label) }
                }
                null -> Text(
                    "Session status is unavailable. Protection changes are paused until Nivara can check the existing session.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SortSelector(sort: AppLockApplicationSort, onSort: (AppLockApplicationSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "Sort applications, currently ${sort.label()}" },
        ) {
            Text("Sort: ${sort.label()}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Name A–Z") },
                onClick = { onSort(AppLockApplicationSort.NAME_A_TO_Z); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("Name Z–A") },
                onClick = { onSort(AppLockApplicationSort.NAME_Z_TO_A); expanded = false },
            )
        }
    }
}

@Composable
private fun ApplicationRow(
    application: InstalledApplication,
    protected: Boolean?,
    pending: Boolean,
    authorized: Boolean,
    storageAvailable: Boolean,
    iconProvider: AndroidApplicationIconProvider,
    onProtectionChange: (Boolean) -> Unit,
) {
    val displayLabel = application.label.takeUnless { it == application.packageName } ?: "Unnamed application"
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PackageIcon(application.packageName, displayLabel, iconProvider)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(displayLabel, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (protected) {
                        true -> "Protected"
                        false -> "Not protected"
                        null -> "Protection state unavailable"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (protected != null) {
                val action = if (protected) "Unprotect" else "Protect"
                OutlinedButton(
                    enabled = authorized && storageAvailable && !pending,
                    onClick = { onProtectionChange(!protected) },
                    modifier = Modifier.semantics { contentDescription = "$action $displayLabel" },
                ) {
                    Text(if (pending) "Updating…" else action)
                }
            }
        }
    }
}

@Composable
private fun PackageIcon(
    packageName: String,
    label: String,
    provider: AndroidApplicationIconProvider,
) {
    val icon: Drawable? = remember(packageName, provider) { provider.loadIcon(packageName) }
    if (icon == null) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(label.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium)
            }
        }
    } else {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    contentDescription = null
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(icon)
                }
            },
            update = { view -> if (view.drawable !== icon) view.setImageDrawable(icon) },
            modifier = Modifier.size(44.dp),
        )
    }
}

@Composable
private fun UnavailableProtectedRow(
    entryNumber: Int,
    pending: Boolean,
    authorized: Boolean,
    storageAvailable: Boolean,
    onUnprotect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Unavailable protected application", style = MaterialTheme.typography.titleSmall)
                Text("Saved entry $entryNumber; its launcher item is not in the latest snapshot.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                enabled = authorized && storageAvailable && !pending,
                onClick = onUnprotect,
                modifier = Modifier.semantics { contentDescription = "Remove protection from unavailable entry $entryNumber" },
            ) {
                Text(if (pending) "Updating…" else "Unprotect")
            }
        }
    }
}

@Composable
private fun ManagementNoticeCard(notice: AppLockManagementNotice) {
    val text = when (notice) {
        AppLockManagementNotice.CHANGES_SAVED -> "Protected-app settings were updated from the repository. Detection will observe the change on its next monitor poll."
        AppLockManagementNotice.ALREADY_IN_STATE -> "No change was needed; the repository already had this protection state."
        AppLockManagementNotice.AUTHENTICATION_REQUIRED -> "Your Nivara session is not active. Return Home and verify the primary credential before changing protection."
        AppLockManagementNotice.SESSION_UNAVAILABLE -> "Nivara could not validate the session. No protection change was made."
        AppLockManagementNotice.APPLICATION_NO_LONGER_AVAILABLE -> "That app is no longer present in the latest launcher snapshot. Protected configuration was left unchanged."
        AppLockManagementNotice.DISCOVERY_UNAVAILABLE -> "App discovery is unavailable, so the requested change could not be checked or saved."
        AppLockManagementNotice.PROTECTED_STATE_UNAVAILABLE -> "Protected-app storage is unavailable. Configuration was not overwritten."
        AppLockManagementNotice.UPDATE_FAILED -> "The update could not be confirmed. Refresh the protected set before trying again."
    }
    Text(text, color = if (notice == AppLockManagementNotice.CHANGES_SAVED || notice == AppLockManagementNotice.ALREADY_IN_STATE) {
        MaterialTheme.colorScheme.primary
    } else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

private fun AppLockApplicationSort.label(): String = when (this) {
    AppLockApplicationSort.NAME_A_TO_Z -> "Name A–Z"
    AppLockApplicationSort.NAME_Z_TO_A -> "Name Z–A"
}

private fun usageAccessText(status: UsageAccessStatus): String = when (status) {
    UsageAccessStatus.GRANTED -> "Granted"
    UsageAccessStatus.NOT_GRANTED -> "Not granted"
    UsageAccessStatus.UNAVAILABLE -> "Unavailable"
}

private fun overlayText(status: com.ashishkumar.nivara.domain.applock.OverlayCapabilityStatus): String = when (status) {
    com.ashishkumar.nivara.domain.applock.OverlayCapabilityStatus.GRANTED -> "Granted"
    com.ashishkumar.nivara.domain.applock.OverlayCapabilityStatus.NOT_GRANTED -> "Not granted"
    com.ashishkumar.nivara.domain.applock.OverlayCapabilityStatus.UNAVAILABLE -> "Unavailable"
}

private fun monitoringText(status: AppLockMonitoringStatus): String = when (status) {
    AppLockMonitoringStatus.Stopped -> "Stopped"
    AppLockMonitoringStatus.Starting -> "Starting"
    AppLockMonitoringStatus.NoProtectedApplications -> "Idle; no apps are configured"
    AppLockMonitoringStatus.Active -> "Active (foreground checks are best-effort)"
    is AppLockMonitoringStatus.Degraded -> when (status.reason) {
        com.ashishkumar.nivara.domain.applock.AppLockDegradedReason.USAGE_ACCESS_NOT_GRANTED -> "Unavailable: Usage Access not granted"
        com.ashishkumar.nivara.domain.applock.AppLockDegradedReason.USAGE_ACCESS_UNAVAILABLE -> "Unavailable: Usage Access status/query"
        com.ashishkumar.nivara.domain.applock.AppLockDegradedReason.OVERLAY_PERMISSION_NOT_GRANTED -> "Unavailable: overlay access not granted"
        com.ashishkumar.nivara.domain.applock.AppLockDegradedReason.OVERLAY_PERMISSION_UNAVAILABLE -> "Unavailable: overlay access status"
        com.ashishkumar.nivara.domain.applock.AppLockDegradedReason.PROTECTED_APPLICATIONS_UNAVAILABLE -> "Unavailable: protected-app storage"
        com.ashishkumar.nivara.domain.applock.AppLockDegradedReason.FOREGROUND_DETECTION_UNAVAILABLE -> "Unavailable: foreground detection"
        com.ashishkumar.nivara.domain.applock.AppLockDegradedReason.SESSION_STATE_UNAVAILABLE -> "Unavailable: session state"
        com.ashishkumar.nivara.domain.applock.AppLockDegradedReason.SERVICE_START_UNAVAILABLE -> "Unavailable: monitoring service start"
    }
}

private fun credentialText(status: CredentialServiceStatus): String = when (status) {
    is CredentialServiceStatus.Configured -> "Configured"
    CredentialServiceStatus.NotConfigured -> "Not configured"
    CredentialServiceStatus.InvalidConfiguration -> "Unavailable"
}
