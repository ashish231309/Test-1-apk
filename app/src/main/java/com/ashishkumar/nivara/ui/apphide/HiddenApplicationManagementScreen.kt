package com.ashishkumar.nivara.ui.apphide

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
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ashishkumar.nivara.data.app.AndroidApplicationIconProvider
import com.ashishkumar.nivara.domain.app.ApplicationDiscoveryResult
import com.ashishkumar.nivara.domain.app.ApplicationRepository
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationsSnapshot
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationsSnapshot
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationRepository
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationRepository
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import com.ashishkumar.nivara.ui.components.NivaraPageHeader
import com.ashishkumar.nivara.ui.components.NivaraSpacing
import com.ashishkumar.nivara.ui.security.SecureScreenEffect

@Composable
fun HiddenApplicationManagementScreen(
    applicationRepository: ApplicationRepository,
    hiddenApplicationRepository: HiddenApplicationRepository,
    protectedApplicationRepository: ProtectedApplicationRepository,
    sessionManager: SessionManager,
    applicationIconProvider: AndroidApplicationIconProvider,
    onBack: () -> Unit,
    onReturnHomeForAuthentication: () -> Unit,
) {
    SecureScreenEffect()
    val factory = remember(
        applicationRepository,
        hiddenApplicationRepository,
        protectedApplicationRepository,
        sessionManager,
    ) {
        HiddenApplicationManagementViewModel.Factory(
            applicationRepository,
            hiddenApplicationRepository,
            protectedApplicationRepository,
            sessionManager,
        )
    }
    val viewModel: HiddenApplicationManagementViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = NivaraSpacing.large, vertical = NivaraSpacing.regular),
            verticalArrangement = Arrangement.spacedBy(NivaraSpacing.medium),
        ) {
            item(key = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(NivaraSpacing.medium)) {
                    NivaraPageHeader(
                        title = "Hidden applications",
                        supportingText = "Manage which apps Nivara's planned launcher will omit. This stores a Nivara preference only; it does not hide apps from Android or other launchers.",
                    )
                    OutlinedButton(onClick = onBack) { Text("Back") }
                }
            }
            when (val current = state) {
                HiddenApplicationManagementState.Loading -> item(key = "loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Reading the application catalogue and hidden state…")
                }
                is HiddenApplicationManagementState.Ready -> hiddenManagementContent(
                    current.content, viewModel, applicationIconProvider, onReturnHomeForAuthentication,
                )
                is HiddenApplicationManagementState.Empty -> hiddenManagementContent(
                    current.content, viewModel, applicationIconProvider, onReturnHomeForAuthentication,
                    emptyMessage = "No launchable applications were discovered.",
                )
                is HiddenApplicationManagementState.NoResults -> hiddenManagementContent(
                    current.content, viewModel, applicationIconProvider, onReturnHomeForAuthentication,
                    emptyMessage = "No applications match this search and section.",
                )
                is HiddenApplicationManagementState.Unavailable -> hiddenManagementContent(
                    current.content, viewModel, applicationIconProvider, onReturnHomeForAuthentication,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.hiddenManagementContent(
    content: HiddenApplicationManagementContent,
    viewModel: HiddenApplicationManagementViewModel,
    iconProvider: AndroidApplicationIconProvider,
    onReturnHomeForAuthentication: () -> Unit,
    emptyMessage: String? = null,
) {
    item(key = "session-access") { HiddenSessionCard(content.sessionState, onReturnHomeForAuthentication) }

    content.notice?.let { notice -> item(key = "notice") { HiddenNoticeCard(notice) } }

    when (content.hiddenApplications) {
        HiddenApplicationsSnapshot.Unreadable -> item(key = "hidden-unreadable") {
            Text(
                "Nivara cannot safely read the saved hidden-app state. Hidden membership is unknown; no entry was treated as visible or cleared, and hide/show actions are disabled.",
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = viewModel::refresh) { Text("Retry hidden-state read") }
        }
        HiddenApplicationsSnapshot.Unavailable -> item(key = "hidden-unavailable") {
            Text(
                "Hidden-app storage is unavailable. Nivara cannot determine which apps are hidden, and changes are disabled.",
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = viewModel::refresh) { Text("Retry hidden-state read") }
        }
        is HiddenApplicationsSnapshot.Available -> Unit
    }
    if (content.discovery == ApplicationDiscoveryResult.Unavailable) {
        item(key = "discovery-unavailable") {
            Text(
                if (content.applications.isEmpty()) {
                    "Application discovery is unavailable. No hidden preferences were changed."
                } else {
                    "Application discovery failed. Showing the last successful catalogue; hide/show actions are paused until a fresh catalogue is available."
                },
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = viewModel::refresh) { Text("Retry application discovery") }
        }
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
                    selected = content.section == HiddenApplicationSection.ALL,
                    onClick = { viewModel.setSection(HiddenApplicationSection.ALL) },
                    label = { Text("All apps") },
                )
                FilterChip(
                    selected = content.section == HiddenApplicationSection.HIDDEN,
                    onClick = { viewModel.setSection(HiddenApplicationSection.HIDDEN) },
                    label = { Text("Hidden") },
                )
            }
            HiddenSortSelector(content.sort, viewModel::setSort)
        }
    }

    val hiddenAvailable = content.hiddenApplications is HiddenApplicationsSnapshot.Available
    val discoveryCurrent = content.discovery is ApplicationDiscoveryResult.Available
    if (content.section == HiddenApplicationSection.HIDDEN && !hiddenAvailable) {
        item(key = "hidden-section-unknown") {
            Text(
                "Hidden applications cannot be listed because the saved state is unreadable or unavailable. This is not an empty hidden list.",
                color = MaterialTheme.colorScheme.error,
            )
        }
    } else if (content.discovery == ApplicationDiscoveryResult.Unavailable && content.applications.isEmpty()) {
        item(key = "catalogue-not-ready") {
            Text("No catalogue is available to list until application discovery succeeds.", color = MaterialTheme.colorScheme.error)
        }
    } else if (content.visibleApplications.isEmpty() && emptyMessage == null) {
        item(key = "section-empty") {
            Text(
                when {
                    !hiddenAvailable -> "Hidden state is unknown; no hidden-list conclusion can be made."
                    content.section == HiddenApplicationSection.HIDDEN && content.staleHiddenPackageNames.isNotEmpty() ->
                        "No currently discovered hidden apps match this view. Saved entries not in the launcher list appear below."
                    content.section == HiddenApplicationSection.HIDDEN -> "No applications are currently hidden by Nivara."
                    else -> "No applications to show."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else if (emptyMessage != null && content.visibleApplications.isEmpty()) {
        item(key = "empty-message") {
            Text(emptyMessage, style = MaterialTheme.typography.bodyMedium)
            if (content.section == HiddenApplicationSection.HIDDEN &&
                content.hiddenApplications is HiddenApplicationsSnapshot.Available &&
                content.hiddenApplications.applications.isEmpty()
            ) {
                Text("No applications are hidden by Nivara.", style = MaterialTheme.typography.bodySmall)
            }
            if (content.discovery is ApplicationDiscoveryResult.Available && content.applications.isEmpty()) {
                OutlinedButton(onClick = viewModel::refresh) { Text("Refresh application list") }
            }
        }
    }

    items(content.visibleApplications, key = { it.application.packageName }) { row ->
        HiddenApplicationRow(
            row = row,
            pending = row.application.packageName in content.pendingPackageNames,
            authorized = content.sessionState is SessionState.Authenticated,
            mutationsAvailable = hiddenAvailable && discoveryCurrent,
            iconProvider = iconProvider,
            onHide = { viewModel.hide(row.application) },
            onUnhide = { viewModel.unhide(row.application) },
        )
    }

    if (content.section == HiddenApplicationSection.ALL && content.staleHiddenPackageNames.isNotEmpty()) {
        item(key = "stale-hidden-hint") {
            Text(
                "Some hidden preferences are not in the current launcher catalogue. Select Hidden to review them.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (content.section == HiddenApplicationSection.HIDDEN && content.staleHiddenPackageNames.isNotEmpty()) {
        item(key = "stale-hidden-heading") {
            Text("Saved hidden preferences not currently discovered", style = MaterialTheme.typography.titleMedium)
            Text(
                "These package preferences are retained while their apps are absent. The same package identity will be hidden again in Nivara's launcher if it is reinstalled. Remove a preference only if you choose to forget it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        itemsIndexed(content.staleHiddenPackageNames, key = { _, packageName -> "stale-hidden-$packageName" }) { index, packageName ->
            StaleHiddenRow(
                number = index + 1,
                pending = packageName in content.pendingPackageNames,
                authorized = content.sessionState is SessionState.Authenticated,
                mutationsAvailable = hiddenAvailable && discoveryCurrent,
                onRemove = { viewModel.removeStaleHiddenPreference(packageName) },
            )
        }
    }

    item(key = "boundary-note") {
        Text(
            "Hidden state is a Nivara preference, not Android-wide concealment. It does not disable, uninstall, or modify another app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HiddenSessionCard(sessionState: SessionState?, onReturnHome: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (sessionState) {
                is SessionState.Authenticated -> Text(
                    "Existing Nivara session active. Hidden-state changes use this session without extending its timeout.",
                    color = MaterialTheme.colorScheme.primary,
                )
                SessionState.Unauthenticated -> {
                    Text("Verify your primary credential from Nivara Home before changing hidden state.")
                    OutlinedButton(onClick = onReturnHome) { Text("Return Home to authenticate") }
                }
                null -> Text(
                    "Session status is unavailable. Hidden-state changes are paused until Nivara can check it.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun HiddenSortSelector(sort: HiddenApplicationSort, onSort: (HiddenApplicationSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "Sort applications, currently ${sort.label()}" },
        ) { Text("Sort: ${sort.label()}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Name A–Z") }, onClick = {
                onSort(HiddenApplicationSort.NAME_A_TO_Z)
                expanded = false
            })
            DropdownMenuItem(text = { Text("Name Z–A") }, onClick = {
                onSort(HiddenApplicationSort.NAME_Z_TO_A)
                expanded = false
            })
        }
    }
}

@Composable
private fun HiddenApplicationRow(
    row: ManagedApplicationState,
    pending: Boolean,
    authorized: Boolean,
    mutationsAvailable: Boolean,
    iconProvider: AndroidApplicationIconProvider,
    onHide: () -> Unit,
    onUnhide: () -> Unit,
) {
    val app = row.application
    val label = app.label.takeUnless { it == app.packageName } ?: "Unnamed application"
    val hiddenText = when (row.hidden) {
        true -> "Hidden"
        false -> "Visible"
        null -> "Unknown"
    }
    val protectedText = when (row.protected) {
        true -> "Protected"
        false -> "Not protected"
        null -> "Unavailable"
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HiddenApplicationIcon(app.packageName, label, iconProvider)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Nivara hidden preference: $hiddenText", style = MaterialTheme.typography.bodySmall)
                Text("App Lock: $protectedText", style = MaterialTheme.typography.bodySmall)
            }
            if (row.hidden != null) {
                val labelAction = if (row.hidden) "Show $label" else "Hide $label"
                OutlinedButton(
                    enabled = authorized && mutationsAvailable && !pending,
                    onClick = if (row.hidden) onUnhide else onHide,
                    modifier = Modifier.semantics { contentDescription = labelAction },
                ) { Text(if (pending) "Saving…" else if (row.hidden) "Show" else "Hide") }
            }
        }
    }
}

@Composable
private fun HiddenApplicationIcon(packageName: String, label: String, provider: AndroidApplicationIconProvider) {
    val icon: Drawable? = remember(packageName, provider) { provider.loadIcon(packageName) }
    if (icon == null) {
        Surface(Modifier.size(44.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
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
private fun StaleHiddenRow(
    number: Int,
    pending: Boolean,
    authorized: Boolean,
    mutationsAvailable: Boolean,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Saved hidden preference", style = MaterialTheme.typography.titleSmall)
                Text("Unavailable launcher entry $number; the saved package identity is not displayed.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                enabled = authorized && mutationsAvailable && !pending,
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = "Remove saved hidden preference $number" },
            ) { Text(if (pending) "Saving…" else "Forget") }
        }
    }
}

@Composable
private fun HiddenNoticeCard(notice: HiddenManagementNotice) {
    val text = when (notice) {
        HiddenManagementNotice.CHANGES_SAVED -> "Nivara hidden-state preference updated and read back from storage."
        HiddenManagementNotice.ALREADY_IN_STATE -> "No change was needed; the repository already had that state."
        HiddenManagementNotice.AUTHENTICATION_REQUIRED -> "Your Nivara session is not active. Return Home and verify the primary credential before changing hidden state."
        HiddenManagementNotice.SESSION_UNAVAILABLE -> "Nivara could not validate the session. No hidden-state change was made."
        HiddenManagementNotice.DISCOVERY_UNAVAILABLE -> "The current application catalogue could not be verified. The saved hidden set was left unchanged."
        HiddenManagementNotice.APPLICATION_NO_LONGER_AVAILABLE -> "That application is no longer in the current launcher catalogue. Its saved hidden preference was left unchanged."
        HiddenManagementNotice.HIDDEN_STATE_UNREADABLE -> "The saved hidden state is unreadable. It was not replaced with an empty set."
        HiddenManagementNotice.HIDDEN_STATE_UNAVAILABLE -> "Hidden-state storage is unavailable. No change was confirmed."
        HiddenManagementNotice.UPDATE_FAILED -> "The update could not be confirmed. Nivara re-read the hidden-state repository; retry when storage is available."
    }
    Text(
        text,
        color = if (notice == HiddenManagementNotice.CHANGES_SAVED || notice == HiddenManagementNotice.ALREADY_IN_STATE) {
            MaterialTheme.colorScheme.primary
        } else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun HiddenApplicationSort.label(): String = when (this) {
    HiddenApplicationSort.NAME_A_TO_Z -> "Name A–Z"
    HiddenApplicationSort.NAME_Z_TO_A -> "Name Z–A"
}
