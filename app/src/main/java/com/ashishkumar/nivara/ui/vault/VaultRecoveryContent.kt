package com.ashishkumar.nivara.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ashishkumar.nivara.domain.vault.VaultRecoveryCodeCodec

/** Minimal, non-exporting setup UI. The code exists only in this in-memory ViewModel/UI flow. */
@Composable
internal fun VaultRecoverySetupContent(state: VaultUiState, viewModel: VaultViewModel) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        state.recoveryMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
        }
        val preview = state.recoverySetupPreview
        if (preview == null) {
            Text(
                "Recovery is optional but must be set up before the installation key is lost. The recovery code is not stored by Nivara; keep it private and separate from this device.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                modifier = Modifier.padding(top = 8.dp),
                enabled = !state.recoveryBusy,
                onClick = viewModel::prepareRecoverySetup,
            ) {
                if (state.recoveryBusy) CircularProgressIndicator()
                else Text("Set up recovery")
            }
        } else {
            Text("Save this one-time recovery code now. Nivara will not copy, share, email, or back it up for you.")
            Text(
                preview.recoveryCode,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Text("Only after you confirm that you saved it will the encrypted recovery wrapper be written to this vault.")
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(enabled = !state.recoveryBusy, onClick = viewModel::confirmRecoverySetup) {
                    if (state.recoveryBusy) CircularProgressIndicator() else Text("I saved the code")
                }
                OutlinedButton(
                    modifier = Modifier.padding(start = 8.dp),
                    enabled = !state.recoveryBusy,
                    onClick = viewModel::cancelRecoverySetup,
                ) { Text("Cancel") }
            }
        }
    }
}

/** Recovery accepts only the bounded human-transfer encoding and never initializes the selected location. */
@Composable
internal fun RecoveryCodeEntry(state: VaultUiState, viewModel: VaultViewModel) {
    var code by remember { mutableStateOf("") }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) code = ""
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    state.recoveryMessage?.let {
        Text(it, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
    }
    OutlinedTextField(
        value = code,
        onValueChange = { value ->
            if (value.length <= VaultRecoveryCodeCodec.MAX_INPUT_CHARS) code = value
        },
        label = { Text("Recovery code") },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        singleLine = false,
        enabled = !state.recoveryBusy,
    )
    Button(
        modifier = Modifier.padding(top = 8.dp),
        enabled = code.isNotBlank() && !state.recoveryBusy,
        onClick = {
            val submitted = code
            code = ""
            viewModel.recover(submitted)
        },
    ) {
        if (state.recoveryBusy) CircularProgressIndicator() else Text("Verify and reconnect vault")
    }
}
