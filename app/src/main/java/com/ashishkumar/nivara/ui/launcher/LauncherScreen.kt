package com.ashishkumar.nivara.ui.launcher

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ashishkumar.nivara.data.app.AndroidApplicationIconProvider
import com.ashishkumar.nivara.domain.app.InstalledApplication
import com.ashishkumar.nivara.domain.security.session.SessionState
import com.ashishkumar.nivara.ui.components.NivaraPageHeader
import com.ashishkumar.nivara.ui.components.NivaraSpacing
import com.ashishkumar.nivara.ui.security.SecureScreenEffect

@Composable
fun LauncherScreen(
    state: LauncherUiState,
    sessionState: SessionState,
    iconProvider: AndroidApplicationIconProvider,
    drawerOpen: Boolean,
    notice: String?,
    onOpenDrawer: () -> Unit,
    onCloseDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onRevealHiddenApps: () -> Unit,
    onHideRevealedApps: () -> Unit,
    onQuickLock: () -> Unit,
    onRetry: () -> Unit,
    onLaunchApplication: (InstalledApplication) -> Unit,
) {
    SecureScreenEffect()
    val renderState = if (state is LauncherUiState.Ready && state.hiddenApplicationsRevealed &&
        sessionState !is SessionState.Authenticated
    ) LauncherUiState.Loading else state
    if (drawerOpen) BackHandler(onBack = onCloseDrawer)
    Scaffold { padding ->
        if (drawerOpen) {
            AppDrawer(
                state = renderState,
                iconProvider = iconProvider,
                contentPadding = padding,
                onBack = onCloseDrawer,
                onRetry = onRetry,
                onLaunchApplication = onLaunchApplication,
            )
        } else {
            LauncherHome(
                state = renderState,
                sessionState = sessionState,
                notice = notice,
                contentPadding = padding,
                onOpenDrawer = onOpenDrawer,
                onOpenSettings = onOpenSettings,
                onRevealHiddenApps = onRevealHiddenApps,
                onHideRevealedApps = onHideRevealedApps,
                onQuickLock = onQuickLock,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun LauncherHome(
    state: LauncherUiState,
    sessionState: SessionState,
    notice: String?,
    contentPadding: PaddingValues,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onRevealHiddenApps: () -> Unit,
    onHideRevealedApps: () -> Unit,
    onQuickLock: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState())
            .padding(horizontal = NivaraSpacing.large, vertical = NivaraSpacing.xLarge),
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small),
        horizontalAlignment = Alignment.Start,
    ) {
        NivaraPageHeader(
            title = "Home",
            supportingText = "Your home for the applications you choose to show here.",
        )
        Button(modifier = Modifier.fillMaxWidth(), onClick = onOpenDrawer) {
            Text("Open app drawer")
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            onClick = onOpenSettings,
        ) { Text("Nivara settings") }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            onClick = if ((state as? LauncherUiState.Ready)?.hiddenApplicationsRevealed == true) {
                onHideRevealedApps
            } else onRevealHiddenApps,
        ) {
            Text(if ((state as? LauncherUiState.Ready)?.hiddenApplicationsRevealed == true) {
                "Stop showing hidden apps"
            } else "Show hidden apps for this session")
        }
        if (sessionState is SessionState.Authenticated) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                onClick = onQuickLock,
            ) { Text("Quick Lock") }
        }
        notice?.let {
            Text(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                text = it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            text = when (state) {
                LauncherUiState.Loading -> "Loading applications and hidden preferences…"
                is LauncherUiState.Ready -> if (state.hiddenApplicationsRevealed) {
                    "Hidden applications are temporarily shown for the current authenticated Nivara session."
                } else if (state.hiddenApplicationCount > 0) {
                    "Hidden applications are omitted from this drawer. They remain installed and available outside Nivara."
                } else "Applications shown here remain installed and launchable in Android."
                LauncherUiState.NoApplications -> "No launchable applications were discovered."
                is LauncherUiState.EmptyVisibleCatalogue -> "All ${state.hiddenApplicationCount} discovered applications are hidden from this drawer."
                is LauncherUiState.HiddenStateUnavailable -> "Hidden-app preferences are ${if (state.unreadable) "unreadable" else "unavailable"}. The drawer is safely closed."
                LauncherUiState.ApplicationDiscoveryUnavailable -> "Application discovery is unavailable. No drawer entries are shown."
                is LauncherUiState.DiscoveryAndHiddenStateUnavailable -> "Application discovery and hidden-app state are unavailable. No drawer entries are shown."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state is LauncherUiState.HiddenStateUnavailable ||
            state is LauncherUiState.ApplicationDiscoveryUnavailable ||
            state is LauncherUiState.DiscoveryAndHiddenStateUnavailable
        ) {
            OutlinedButton(modifier = Modifier.padding(top = 8.dp), onClick = onRetry) { Text("Retry") }
        }
        Text(
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
            text = "Nivara hides apps only from its own drawer. Android Settings and other launchers are unaffected.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AppDrawer(
    state: LauncherUiState,
    iconProvider: AndroidApplicationIconProvider,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLaunchApplication: (InstalledApplication) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack) { Text("Home") }
            Text("App drawer", style = MaterialTheme.typography.headlineSmall)
        }
        when (state) {
            LauncherUiState.Loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Refreshing applications and hidden preferences…", Modifier.padding(20.dp))
            }
            is LauncherUiState.Ready -> {
                if (state.hiddenApplicationsRevealed) {
                    Text(
                        "Hidden apps are temporarily included for this authenticated session.",
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.applications, key = InstalledApplication::packageName) { application ->
                        ApplicationCard(application, iconProvider, onLaunchApplication)
                    }
                }
            }
            LauncherUiState.NoApplications -> EmptyMessage(
                title = "No applications to show",
                detail = "Nivara could not find a launchable app in the current catalogue.",
                onRetry = onRetry,
            )
            is LauncherUiState.EmptyVisibleCatalogue -> EmptyMessage(
                title = "No visible applications",
                detail = "All ${state.hiddenApplicationCount} discovered applications are hidden from Nivara's normal drawer. Use the explicit reveal control on Home after authenticating.",
                onRetry = onRetry,
            )
            is LauncherUiState.HiddenStateUnavailable -> EmptyMessage(
                title = "Hidden-app state unavailable",
                detail = "Nivara will not show apps until it can safely read which applications are hidden. Your saved preference was not changed.",
                onRetry = onRetry,
            )
            LauncherUiState.ApplicationDiscoveryUnavailable -> EmptyMessage(
                title = "Application discovery unavailable",
                detail = "No applications are shown because the current launchable-app catalogue could not be read.",
                onRetry = onRetry,
            )
            is LauncherUiState.DiscoveryAndHiddenStateUnavailable -> EmptyMessage(
                title = "Launcher data unavailable",
                detail = "Neither the app catalogue nor hidden preferences could be confirmed. No application entries are shown.",
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun EmptyMessage(title: String, detail: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(modifier = Modifier.padding(top = 12.dp), onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun ApplicationCard(
    application: InstalledApplication,
    iconProvider: AndroidApplicationIconProvider,
    onLaunchApplication: (InstalledApplication) -> Unit,
) {
    val label = application.label.takeUnless { it == application.packageName } ?: "Unnamed application"
    Card(
        onClick = { onLaunchApplication(application) },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Launch $label" },
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ApplicationIcon(application.packageName, label, iconProvider)
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Open", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ApplicationIcon(
    packageName: String,
    label: String,
    iconProvider: AndroidApplicationIconProvider,
) {
    val icon: Drawable? = remember(packageName, iconProvider) { iconProvider.loadIcon(packageName) }
    if (icon == null) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(label.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleLarge)
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
            modifier = Modifier.size(48.dp),
        )
    }
}
