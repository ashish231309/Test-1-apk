package com.ashishkumar.nivara

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.ashishkumar.nivara.ui.NivaraApp
import com.ashishkumar.nivara.ui.theme.NivaraTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val container by lazy { (application as NivaraApplication).container }
    private val foregroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitoringStartJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val biometricAuthenticator = container.biometricAuthenticator(this)
        setContent {
            NivaraTheme {
                NivaraApp(
                    primaryCredentialService = container.primaryCredentialService,
                    biometricAuthenticator = biometricAuthenticator,
                    sessionManager = container.sessionManager,
                    appLockMonitor = container.appLockMonitor,
                    appLockMonitoringController = container.appLockMonitoringController,
                    applicationRepository = container.applicationRepository,
                    protectedApplicationRepository = container.protectedApplicationRepository,
                    hiddenApplicationRepository = container.hiddenApplicationRepository,
                    applicationIconProvider = container.applicationIconProvider,
                    usageAccessRepository = container.usageAccessRepository,
                    overlayCapabilityRepository = container.overlayCapabilityRepository,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        monitoringStartJob?.cancel()
        monitoringStartJob = foregroundScope.launch {
            container.sessionManager.currentState()
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
}
