package com.ashishkumar.nivara.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.unit.dp
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialService
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialType
import com.ashishkumar.nivara.ui.credentials.CredentialEditorScreen
import com.ashishkumar.nivara.ui.credentials.CredentialFlowMode
import com.ashishkumar.nivara.ui.credentials.CredentialHomeScreen
import com.ashishkumar.nivara.ui.credentials.CredentialTypeSelectionScreen

@Composable
fun NivaraApp(primaryCredentialService: PrimaryCredentialService) {
    val navController = rememberNavController()
    var homeRefreshKey by remember { mutableIntStateOf(0) }

    NavHost(navController = navController, startDestination = HOME) {
        composable(HOME) {
            CredentialHomeScreen(
                service = primaryCredentialService,
                refreshKey = homeRefreshKey,
                onEnroll = { navController.navigate(SELECT_TYPE) },
                onVerify = { type -> navController.navigate("$VERIFY/${type.storageValue}") },
                onChange = { type -> navController.navigate("$CHANGE/${type.storageValue}") },
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
            if (type == null) {
                InvalidCredentialRoute { navController.popBackStack() }
            } else {
                CredentialEditorScreen(
                    service = primaryCredentialService,
                    mode = CredentialFlowMode.ENROLL,
                    type = type,
                    onBack = { navController.popBackStack() },
                    onDone = {
                        homeRefreshKey++
                        navController.popBackStack(HOME, inclusive = false)
                    },
                )
            }
        }
        composable(
            route = "$VERIFY/{type}",
            arguments = listOf(navArgument("type") { type = NavType.StringType }),
        ) { entry ->
            val type = PrimaryCredentialType.fromStorageValue(entry.arguments?.getString("type").orEmpty())
            if (type == null) {
                InvalidCredentialRoute { navController.popBackStack() }
            } else {
                CredentialEditorScreen(
                    service = primaryCredentialService,
                    mode = CredentialFlowMode.VERIFY,
                    type = type,
                    onBack = { navController.popBackStack() },
                    onDone = {
                        homeRefreshKey++
                        navController.popBackStack(HOME, inclusive = false)
                    },
                )
            }
        }
        composable(
            route = "$CHANGE/{type}",
            arguments = listOf(navArgument("type") { type = NavType.StringType }),
        ) { entry ->
            val type = PrimaryCredentialType.fromStorageValue(entry.arguments?.getString("type").orEmpty())
            if (type == null) {
                InvalidCredentialRoute { navController.popBackStack() }
            } else {
                CredentialEditorScreen(
                    service = primaryCredentialService,
                    mode = CredentialFlowMode.CHANGE,
                    type = type,
                    onBack = { navController.popBackStack() },
                    onDone = {
                        homeRefreshKey++
                        navController.popBackStack(HOME, inclusive = false)
                    },
                )
            }
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
