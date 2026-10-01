package com.ashishkumar.nivara

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.FragmentActivity
import com.ashishkumar.nivara.data.vault.VaultRootPickerContract
import com.ashishkumar.nivara.data.vault.VaultSourcePickerContract
import com.ashishkumar.nivara.domain.vault.VaultRootSelectionResult
import com.ashishkumar.nivara.domain.vault.content.VaultSourceSelectionResult
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
    private lateinit var vaultRootPicker: ActivityResultLauncher<Unit>
    private lateinit var vaultSourcePicker: ActivityResultLauncher<Unit>
    private var vaultRootSelectionResult by mutableStateOf<VaultRootSelectionResult?>(null)
    private var vaultSourceSelectionResult by mutableStateOf<VaultSourceSelectionResult?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vaultRootPicker = registerForActivityResult(
            VaultRootPickerContract(container.vaultRootSelectionHandler),
        ) { result -> vaultRootSelectionResult = result }
        vaultSourcePicker = registerForActivityResult(
            VaultSourcePickerContract(container.pendingVaultSources),
        ) { result -> vaultSourceSelectionResult = result }
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
                    vaultRepository = container.vaultRepository,
                    vaultIndexRepository = container.vaultIndexRepository,
                    vaultOrganizationRepository = container.vaultOrganizationRepository,
                    vaultImportRepository = container.vaultImportRepository,
                    vaultContentPresentationGateway = container.vaultContentPresentationGateway,
                    rootSelectionResult = vaultRootSelectionResult,
                    sourceSelectionResult = vaultSourceSelectionResult,
                    onConsumeRootSelectionResult = { vaultRootSelectionResult = null },
                    onConsumeSourceSelectionResult = { vaultSourceSelectionResult = null },
                    onChooseVaultRoot = { vaultRootPicker.launch(Unit) },
                    onChooseVaultSource = { vaultSourcePicker.launch(Unit) },
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
