package com.ashishkumar.nivara.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ashishkumar.nivara.domain.app.ApplicationRepository
import com.ashishkumar.nivara.data.app.AndroidApplicationIconProvider
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationRepository
import com.ashishkumar.nivara.domain.applock.AppLockMonitor
import com.ashishkumar.nivara.domain.applock.AppLockMonitoringController
import com.ashishkumar.nivara.ui.applock.AppLockManagementScreen
import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticator
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialService
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialType
import com.ashishkumar.nivara.domain.permissions.UsageAccessRepository
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityRepository
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.ui.credentials.CredentialEditorScreen
import com.ashishkumar.nivara.ui.credentials.CredentialFlowMode
import com.ashishkumar.nivara.ui.credentials.CredentialHomeScreen
import com.ashishkumar.nivara.ui.credentials.CredentialTypeSelectionScreen
import com.ashishkumar.nivara.ui.navigation.AppDestination
import com.ashishkumar.nivara.ui.setup.AppLockSetupScreen

@Composable
fun NivaraApp(
    primaryCredentialService: PrimaryCredentialService,
    biometricAuthenticator: BiometricAuthenticator,
    sessionManager: SessionManager,
    appLockMonitor: AppLockMonitor,
    appLockMonitoringController: AppLockMonitoringController,
    applicationRepository: ApplicationRepository,
    protectedApplicationRepository: ProtectedApplicationRepository,
    applicationIconProvider: AndroidApplicationIconProvider,
    usageAccessRepository: UsageAccessRepository,
    overlayCapabilityRepository: OverlayCapabilityRepository,
) {
    val navController = rememberNavController()
    var homeRefreshKey by remember { mutableIntStateOf(0) }
    val finishEditor: () -> Unit = {
        homeRefreshKey++
        navController.popBackStack(HOME, inclusive = false)
        Unit
    }

    NavHost(navController = navController, startDestination = HOME) {
        composable(HOME) {
            CredentialHomeScreen(
                service = primaryCredentialService,
                biometricAuthenticator = biometricAuthenticator,
                sessionManager = sessionManager,
                refreshKey = homeRefreshKey,
                onEnroll = { navController.navigate(SELECT_TYPE) },
                onVerify = { type -> navController.navigate("$VERIFY/${type.storageValue}") },
                onChange = { type -> navController.navigate("$CHANGE/${type.storageValue}") },
                onEnableBiometric = { type -> navController.navigate("$BIOMETRIC_ENABLE/${type.storageValue}") },
                onDisableBiometric = { type -> navController.navigate("$BIOMETRIC_DISABLE/${type.storageValue}") },
                onPrepareAppLock = { navController.navigate(AppDestination.AppLockSetup.route) },
            )
        }
        composable(AppDestination.AppLockSetup.route) {
            AppLockSetupScreen(
                applicationRepository = applicationRepository,
                usageAccessRepository = usageAccessRepository,
                overlayCapabilityRepository = overlayCapabilityRepository,
                onBack = { navController.popBackStack() },
                onManageProtectedApps = { navController.navigate(AppDestination.AppLockManagement.route) },
            )
        }
        composable(AppDestination.AppLockManagement.route) {
            AppLockManagementScreen(
                applicationRepository = applicationRepository,
                protectedApplicationRepository = protectedApplicationRepository,
                usageAccessRepository = usageAccessRepository,
                overlayCapabilityRepository = overlayCapabilityRepository,
                primaryCredentialService = primaryCredentialService,
                sessionManager = sessionManager,
                appLockMonitor = appLockMonitor,
                appLockMonitoringController = appLockMonitoringController,
                applicationIconProvider = applicationIconProvider,
                onBack = { navController.popBackStack() },
                onOpenSetup = { navController.navigate(AppDestination.AppLockSetup.route) },
                onReturnHomeForAuthentication = { navController.popBackStack(HOME, inclusive = false) },
            )
        }
        composable(SELECT_TYPE) {
            CredentialTypeSelectionScreen(
                onBack = { navController.popBackStack() },
                onSelected = { type -> navController.navigate("$ENROLL/${type.storageValue}") },
            )
        }
        composable(
            route = "$ENROLL/{type}",
            arguments = listOf(navArgument("type") { type = NavType.StringType }),
        ) { entry ->
            val type = PrimaryCredentialType.fromStorageValue(entry.arguments?.getString("type").orEmpty())
            if (type == null) InvalidCredentialRoute { navController.popBackStack() }
            else CredentialEditorScreen(
                service = primaryCredentialService,
                biometricAuthenticator = biometricAuthenticator,
                sessionManager = sessionManager,
                mode = CredentialFlowMode.ENROLL,
                type = type,
                onBack = { navController.popBackStack() },
                onDone = finishEditor,
            )
        }
        composable(
            route = "$VERIFY/{type}",
            arguments = listOf(navArgument("type") { type = NavType.StringType }),
        ) { entry ->
            val type = PrimaryCredentialType.fromStorageValue(entry.arguments?.getString("type").orEmpty())
            if (type == null) InvalidCredentialRoute { navController.popBackStack() }
            else CredentialEditorScreen(
                service = primaryCredentialService,
                biometricAuthenticator = biometricAuthenticator,
                sessionManager = sessionManager,
                mode = CredentialFlowMode.VERIFY,
                type = type,
                onBack = { navController.popBackStack() },
                onDone = finishEditor,
            )
        }
        composable(
            route = "$CHANGE/{type}",
            arguments = listOf(navArgument("type") { type = NavType.StringType }),
        ) { entry ->
            val type = PrimaryCredentialType.fromStorageValue(entry.arguments?.getString("type").orEmpty())
            if (type == null) InvalidCredentialRoute { navController.popBackStack() }
            else CredentialEditorScreen(
                service = primaryCredentialService,
                biometricAuthenticator = biometricAuthenticator,
                sessionManager = sessionManager,
                mode = CredentialFlowMode.CHANGE,
                type = type,
                onBack = { navController.popBackStack() },
                onDone = finishEditor,
            )
        }
        composable(
            route = "$BIOMETRIC_ENABLE/{type}",
            arguments = listOf(navArgument("type") { type = NavType.StringType }),
        ) { entry ->
            val type = PrimaryCredentialType.fromStorageValue(entry.arguments?.getString("type").orEmpty())
            if (type == null) InvalidCredentialRoute { navController.popBackStack() }
            else CredentialEditorScreen(
                service = primaryCredentialService,
                biometricAuthenticator = biometricAuthenticator,
                sessionManager = sessionManager,
                mode = CredentialFlowMode.BIOMETRIC_ENABLE,
                type = type,
                onBack = { navController.popBackStack() },
                onDone = finishEditor,
            )
        }
        composable(
            route = "$BIOMETRIC_DISABLE/{type}",
            arguments = listOf(navArgument("type") { type = NavType.StringType }),
        ) { entry ->
            val type = PrimaryCredentialType.fromStorageValue(entry.arguments?.getString("type").orEmpty())
            if (type == null) InvalidCredentialRoute { navController.popBackStack() }
            else CredentialEditorScreen(
                service = primaryCredentialService,
                biometricAuthenticator = biometricAuthenticator,
                sessionManager = sessionManager,
                mode = CredentialFlowMode.BIOMETRIC_DISABLE,
                type = type,
                onBack = { navController.popBackStack() },
                onDone = finishEditor,
            )
        }
    }
}

@Composable
private fun InvalidCredentialRoute(onBack: () -> Unit) {
    androidx.compose.material3.Scaffold { padding ->
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier.padding(padding).padding(24.dp),
        ) {
            androidx.compose.material3.Text("Credential setup is unavailable.")
            androidx.compose.material3.Button(onClick = onBack) {
                androidx.compose.material3.Text("Back")
            }
        }
    }
}

private const val HOME = "home"
private const val SELECT_TYPE = "credential/select-type"
private const val ENROLL = "credential/enroll"
private const val VERIFY = "credential/verify"
private const val CHANGE = "credential/change"
private const val BIOMETRIC_ENABLE = "biometric/enable"
private const val BIOMETRIC_DISABLE = "biometric/disable"
