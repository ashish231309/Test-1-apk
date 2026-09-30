package com.ashishkumar.nivara.ui.credentials

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ashishkumar.nivara.R
import com.ashishkumar.nivara.domain.credentials.CredentialServiceStatus
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialService
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialType

@Composable
fun CredentialHomeScreen(
    service: PrimaryCredentialService,
    refreshKey: Int,
    onEnroll: () -> Unit,
    onVerify: (PrimaryCredentialType) -> Unit,
    onChange: (PrimaryCredentialType) -> Unit,
) {
    var status by remember { mutableStateOf<CredentialServiceStatus?>(null) }
    LaunchedEffect(service, refreshKey) { status = service.status() }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Nivara", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                text = stringResource(R.string.home_welcome),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            when (val current = status) {
                null -> CircularProgressIndicator()
                CredentialServiceStatus.NotConfigured -> Button(onClick = onEnroll) {
                    Text(stringResource(R.string.credential_setup))
                }
                is CredentialServiceStatus.Configured -> {
                    Text(stringResource(R.string.credential_configured, current.type.displayName()))
                    Button(modifier = Modifier.padding(top = 12.dp), onClick = { onVerify(current.type) }) {
                        Text(stringResource(R.string.credential_verify))
                    }
                    OutlinedButton(modifier = Modifier.padding(top = 8.dp), onClick = { onChange(current.type) }) {
                        Text(stringResource(R.string.credential_change))
                    }
                }
                CredentialServiceStatus.InvalidConfiguration -> Text(
                    stringResource(R.string.credential_configuration_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun CredentialTypeSelectionScreen(
    onBack: () -> Unit,
    onSelected: (PrimaryCredentialType) -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.credential_select_title), style = MaterialTheme.typography.headlineSmall)
            PrimaryCredentialType.entries.forEach { type ->
                Button(modifier = Modifier.padding(top = 12.dp), onClick = { onSelected(type) }) {
                    Text(type.displayName())
                }
            }
            OutlinedButton(modifier = Modifier.padding(top = 20.dp), onClick = onBack) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
fun PrimaryCredentialType.displayName(): String = stringResource(
    when (this) {
        PrimaryCredentialType.PIN -> R.string.credential_type_pin
        PrimaryCredentialType.PASSWORD -> R.string.credential_type_password
        PrimaryCredentialType.PATTERN -> R.string.credential_type_pattern
    },
)
