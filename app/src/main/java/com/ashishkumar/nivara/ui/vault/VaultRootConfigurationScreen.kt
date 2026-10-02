package com.ashishkumar.nivara.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import com.ashishkumar.nivara.domain.vault.VaultRootSelectionResult
import com.ashishkumar.nivara.ui.components.NivaraPageHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Explicit root configuration destination; URI and picker mechanics remain in the data/activity boundary. */
@Composable
fun VaultRootConfigurationScreen(
    sessionManager: SessionManager,
    selectionResult: VaultRootSelectionResult?,
    onChooseRoot: () -> Unit,
    onBack: () -> Unit,
    onReturnHomeForAuthentication: () -> Unit,
) {
    val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var checkingSession by remember { mutableStateOf(true) }
    var authorized by remember { mutableStateOf(false) }

    LaunchedEffect(sessionState) {
        checkingSession = true
        authorized = sessionAllowed(sessionManager)
        checkingSession = false
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NivaraPageHeader(
                title = "External vault folder",
                supportingText = "Choose a shared folder explicitly, or pick the exact previously selected folder to reconnect it. A different folder will not replace the current vault location.",
            )
            selectionResult?.let { result ->
                Text(selectionMessage(result), modifier = Modifier.padding(bottom = 12.dp))
            }
            when {
                checkingSession -> CircularProgressIndicator()
                !authorized || sessionState !is SessionState.Authenticated -> {
                    Text("Authenticate from Nivara Home before configuring vault storage.")
                    Button(modifier = Modifier.padding(top = 12.dp), onClick = onReturnHomeForAuthentication) {
                        Text("Return to Nivara Home")
                    }
                }
                else -> Button(onClick = {
                    scope.launch {
                        authorized = sessionAllowed(sessionManager)
                        if (authorized) onChooseRoot()
                    }
                }) {
                    Text("Choose or reconnect folder")
                }
            }
            OutlinedButton(modifier = Modifier.padding(top = 24.dp), onClick = onBack) {
                Text("Return to vault")
            }
        }
    }
}

private suspend fun sessionAllowed(sessionManager: SessionManager): Boolean = try {
    sessionManager.currentState() is SessionState.Authenticated &&
        sessionManager.mayAccessSensitiveContent()
} catch (failure: CancellationException) {
    throw failure
} catch (_: Exception) {
    false
}

private fun selectionMessage(result: VaultRootSelectionResult): String = when (result) {
    VaultRootSelectionResult.SELECTED -> "The external folder was selected. Return to the vault to inspect it."
    VaultRootSelectionResult.RECONNECTED -> "The previously selected external folder was reconnected. Return to the vault to inspect it."
    VaultRootSelectionResult.CANCELLED -> "Folder selection was cancelled; the saved selection was not changed."
    VaultRootSelectionResult.INVALID_SELECTION -> "The selected item is not a writable document folder."
    VaultRootSelectionResult.DIFFERENT_ROOT_ALREADY_SELECTED -> "A different folder is already bound; the saved location was not replaced."
    VaultRootSelectionResult.ACCESS_DENIED -> "Android did not grant persistent read and write access."
    VaultRootSelectionResult.UNAVAILABLE -> "Android could not access the selected folder."
    VaultRootSelectionResult.PERSISTENCE_FAILURE -> "The exact folder selection could not be saved. No alternate location was chosen."
}
