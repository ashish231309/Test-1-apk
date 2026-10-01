package com.ashishkumar.nivara

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ashishkumar.nivara.ui.launcher.LauncherScreen
import com.ashishkumar.nivara.ui.launcher.LauncherViewModel
import com.ashishkumar.nivara.ui.launcher.RevealRequestResult
import com.ashishkumar.nivara.ui.theme.NivaraTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The single intentionally exported Android Home entry point. Settings remain in non-exported MainActivity. */
class LauncherActivity : FragmentActivity() {
    private val container by lazy { (application as NivaraApplication).container }
    private val viewModel: LauncherViewModel by viewModels {
        LauncherViewModel.Factory(
            container.applicationRepository,
            container.hiddenApplicationRepository,
            container.sessionManager,
        )
    }
    private val foregroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitoringStartJob: Job? = null

    private var drawerOpen by mutableStateOf(false)
    private var notice by mutableStateOf<String?>(null)
    private var revealRequestPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val sessionState by container.sessionManager.sessionState.collectAsStateWithLifecycle()
            NivaraTheme {
                LauncherScreen(
                    state = state,
                    sessionState = sessionState,
                    iconProvider = container.applicationIconProvider,
                    drawerOpen = drawerOpen,
                    notice = notice,
                    onOpenDrawer = {
                        foregroundScope.launch {
                            viewModel.refresh()
                            drawerOpen = true
                        }
                    },
                    onCloseDrawer = { drawerOpen = false },
                    onOpenSettings = { startActivity(Intent(this, MainActivity::class.java)) },
                    onRevealHiddenApps = {
                        foregroundScope.launch {
                            handleRevealResult(viewModel.revealHiddenApplications(), allowAuthenticationRoute = true)
                        }
                    },
                    onHideRevealedApps = {
                        foregroundScope.launch {
                            viewModel.hideRevealedApplications()
                            notice = "Hidden applications are no longer shown in Nivara."
                        }
                    },
                    onQuickLock = {
                        foregroundScope.launch {
                            notice = if (viewModel.quickLock()) {
                                "Quick Lock cleared the Nivara session and temporary reveal."
                            } else {
                                "The temporary reveal was cleared, but Nivara could not confirm session lock."
                            }
                        }
                    },
                    onRetry = { foregroundScope.launch { viewModel.refresh() } },
                    onLaunchApplication = ::launchApplication,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        monitoringStartJob?.cancel()
        val resumeRevealRequest = revealRequestPending
        revealRequestPending = false
        monitoringStartJob = foregroundScope.launch {
            viewModel.refresh()
            if (resumeRevealRequest) {
                handleRevealResult(viewModel.revealHiddenApplications(), allowAuthenticationRoute = false)
            }
            container.appLockMonitoringController.start()
        }
    }

    override fun onPause() {
        monitoringStartJob?.cancel()
        monitoringStartJob = null
        super.onPause()
    }

    override fun onDestroy() {
        foregroundScope.cancel()
        super.onDestroy()
    }

    private suspend fun handleRevealResult(result: RevealRequestResult, allowAuthenticationRoute: Boolean) {
        when (result) {
            RevealRequestResult.REVEALED -> {
                drawerOpen = true
                notice = "Hidden applications are temporarily shown for the authenticated Nivara session."
            }
            RevealRequestResult.AUTHENTICATION_REQUIRED -> {
                if (allowAuthenticationRoute) {
                    revealRequestPending = true
                    try {
                        startActivity(Intent(this, MainActivity::class.java))
                    } catch (_: RuntimeException) {
                        revealRequestPending = false
                        notice = "Nivara could not open its credential settings. Hidden applications remain omitted."
                    }
                } else {
                    notice = "Authentication was not completed. Hidden applications remain omitted."
                }
            }
            RevealRequestResult.SESSION_UNAVAILABLE ->
                notice = "Nivara could not validate the session. Hidden applications remain omitted."
            RevealRequestResult.NO_HIDDEN_APPLICATIONS ->
                notice = "No currently discovered applications are hidden."
            RevealRequestResult.HIDDEN_STATE_UNAVAILABLE ->
                notice = "Hidden-app preferences could not be confirmed. No hidden applications are shown."
            RevealRequestResult.APPLICATION_DISCOVERY_UNAVAILABLE ->
                notice = "Application discovery is unavailable. No applications are shown."
        }
    }

    private fun launchApplication(application: com.ashishkumar.nivara.domain.app.InstalledApplication) {
        val launchIntent = try {
            packageManager.getLaunchIntentForPackage(application.packageName)
        } catch (_: RuntimeException) {
            null
        }
        val launchPackage = launchIntent?.component?.packageName ?: launchIntent?.getPackage()
        if (launchIntent == null || launchPackage != application.packageName) {
            notice = "This application is no longer launchable. Refresh the app drawer and try again."
            foregroundScope.launch { viewModel.refresh() }
            return
        }
        try {
            startActivity(launchIntent)
            notice = null
        } catch (_: ActivityNotFoundException) {
            notice = "This application could not be opened. Refresh the app drawer and try again."
            foregroundScope.launch { viewModel.refresh() }
        } catch (_: SecurityException) {
            notice = "Android did not allow this application to open. Refresh the app drawer to check its availability."
            foregroundScope.launch { viewModel.refresh() }
        } catch (_: RuntimeException) {
            notice = "This application could not be opened. Refresh the app drawer and try again."
            foregroundScope.launch { viewModel.refresh() }
        }
    }
}
