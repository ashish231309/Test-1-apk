package com.ashishkumar.nivara.ui.credentials

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ashishkumar.nivara.R
import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.credentials.CredentialChangeResult
import com.ashishkumar.nivara.domain.credentials.CredentialRejection
import com.ashishkumar.nivara.domain.credentials.CredentialRules
import com.ashishkumar.nivara.domain.credentials.EnrollmentResult
import com.ashishkumar.nivara.domain.credentials.InvalidCredentialInput
import com.ashishkumar.nivara.domain.credentials.PatternCanonicalizer
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialService
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal enum class CredentialFlowMode { ENROLL, VERIFY, CHANGE }

@Composable
internal fun CredentialEditorScreen(
    service: PrimaryCredentialService,
    mode: CredentialFlowMode,
    type: PrimaryCredentialType,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var busy by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var targetType by remember { mutableStateOf(type) }

    // Deliberately remember-only: credential drafts are not written to SavedState or restored after recreation.
    var currentText by remember { mutableStateOf("") }
    var firstText by remember { mutableStateOf("") }
    var confirmationText by remember { mutableStateOf("") }
    var currentPattern by remember { mutableStateOf(emptyList<Int>()) }
    var firstPattern by remember { mutableStateOf(emptyList<Int>()) }
    var confirmationPattern by remember { mutableStateOf(emptyList<Int>()) }
    BackHandler { if (success) onDone() else onBack() }

    fun clearDrafts() {
        currentText = ""
        firstText = ""
        confirmationText = ""
        currentPattern = emptyList()
        firstPattern = emptyList()
        confirmationPattern = emptyList()
    }

    fun perform() {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            var currentChars: CharArray? = null
            var firstChars: CharArray? = null
            var confirmationChars: CharArray? = null
            try {
                when (mode) {
                    CredentialFlowMode.VERIFY -> {
                        val submitted = toCredentialChars(type, currentText, currentPattern)
                        currentChars = submitted
                        when (val result = service.authenticate(submitted)) {
                            is AuthenticationResult.Authenticated -> {
                                success = true
                                message = "Credential verified successfully."
                            }
                            AuthenticationResult.Failed -> message = "The credential was not accepted."
                            is AuthenticationResult.TemporarilyBlocked -> {
                                val seconds = ((result.retryAfterMillis + 999) / 1_000).coerceAtLeast(1)
                                message = "Please wait $seconds seconds before trying again."
                            }
                            AuthenticationResult.InvalidConfiguration -> message = "Credential configuration is unavailable."
                            AuthenticationResult.NotConfigured -> message = "No primary credential is configured."
                        }
                    }
                    CredentialFlowMode.ENROLL -> {
                        val submitted = toCredentialChars(type, firstText, firstPattern)
                        firstChars = submitted
                        val confirmed = toCredentialChars(type, confirmationText, confirmationPattern)
                        confirmationChars = confirmed
                        when (val result = service.enroll(type, submitted, confirmed)) {
                            is EnrollmentResult.Enrolled -> {
                                success = true
                                message = "Primary credential enrolled successfully."
                            }
                            EnrollmentResult.AlreadyConfigured -> message = "A primary credential is already configured."
                            EnrollmentResult.ConfirmationMismatch -> message = "The entries do not match. Try again."
                            is EnrollmentResult.Rejected -> message = rejectionMessage(result.reason)
                            EnrollmentResult.StorageFailure -> message = "Credential configuration could not be saved."
                        }
                    }
                    CredentialFlowMode.CHANGE -> {
                        val current = toCredentialChars(type, currentText, currentPattern)
                        currentChars = current
                        val submitted = toCredentialChars(targetType, firstText, firstPattern)
                        firstChars = submitted
                        val confirmed = toCredentialChars(targetType, confirmationText, confirmationPattern)
                        confirmationChars = confirmed
                        when (val result = service.changePrimary(current, targetType, submitted, confirmed)) {
                            is CredentialChangeResult.Changed -> {
                                success = true
                                message = "Primary credential changed successfully."
                            }
                            CredentialChangeResult.NotConfigured -> message = "No primary credential is configured."
                            CredentialChangeResult.AuthenticationFailed -> message = "The current credential was not accepted."
                            is CredentialChangeResult.TemporarilyBlocked -> {
                                val seconds = ((result.retryAfterMillis + 999) / 1_000).coerceAtLeast(1)
                                message = "Please wait $seconds seconds before trying again."
                            }
                            CredentialChangeResult.ConfirmationMismatch -> message = "The new entries do not match. Try again."
                            is CredentialChangeResult.Rejected -> message = rejectionMessage(result.reason)
                            CredentialChangeResult.InvalidConfiguration -> message = "Credential configuration is unavailable."
                            CredentialChangeResult.StorageFailure -> message = "Credential configuration could not be saved."
                        }
                    }
                }
            } catch (failure: InvalidCredentialInput) {
                message = rejectionMessage(failure.reason)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                message = "The credential operation could not be completed."
            } finally {
                currentChars?.fill('\u0000')
                firstChars?.fill('\u0000')
                confirmationChars?.fill('\u0000')
                clearDrafts()
                keyboard?.hide()
                busy = false
            }
        }
    }

    val title = when (mode) {
        CredentialFlowMode.ENROLL -> stringResource(R.string.credential_enroll_title, type.displayName())
        CredentialFlowMode.VERIFY -> stringResource(R.string.credential_verify_title, type.displayName())
        CredentialFlowMode.CHANGE -> stringResource(R.string.credential_change_title)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (success) {
                Text(message.orEmpty(), color = MaterialTheme.colorScheme.primary)
                Button(onClick = onDone) { Text(stringResource(R.string.done)) }
            } else {
                when (mode) {
                    CredentialFlowMode.VERIFY -> CredentialEntry(
                        type = type,
                        label = stringResource(R.string.credential_enter_label, type.displayName()),
                        text = currentText,
                        onTextChange = { currentText = it },
                        pattern = currentPattern,
                        onPatternChange = { currentPattern = it },
                    )
                    CredentialFlowMode.ENROLL -> {
                        CredentialEntry(
                            type = type,
                            label = stringResource(R.string.credential_enter_label, type.displayName()),
                            text = firstText,
                            onTextChange = { firstText = it },
                            pattern = firstPattern,
                            onPatternChange = { firstPattern = it },
                        )
                        CredentialEntry(
                            type = type,
                            label = stringResource(R.string.credential_confirm_label, type.displayName()),
                            text = confirmationText,
                            onTextChange = { confirmationText = it },
                            pattern = confirmationPattern,
                            onPatternChange = { confirmationPattern = it },
                        )
                    }
                    CredentialFlowMode.CHANGE -> {
                        Text(stringResource(R.string.credential_new_type))
                        PrimaryCredentialType.entries.forEach { candidate ->
                            if (candidate == targetType) {
                                Button(onClick = {}) { Text(candidate.displayName()) }
                            } else {
                                OutlinedButton(onClick = {
                                    targetType = candidate
                                    firstText = ""
                                    confirmationText = ""
                                    firstPattern = emptyList()
                                    confirmationPattern = emptyList()
                                }) { Text(candidate.displayName()) }
                            }
                        }
                        CredentialEntry(
                            type = type,
                            label = stringResource(R.string.credential_current_label, type.displayName()),
                            text = currentText,
                            onTextChange = { currentText = it },
                            pattern = currentPattern,
                            onPatternChange = { currentPattern = it },
                        )
                        CredentialEntry(
                            type = targetType,
                            label = stringResource(R.string.credential_new_label, targetType.displayName()),
                            text = firstText,
                            onTextChange = { firstText = it },
                            pattern = firstPattern,
                            onPatternChange = { firstPattern = it },
                        )
                        CredentialEntry(
                            type = targetType,
                            label = stringResource(R.string.credential_confirm_label, targetType.displayName()),
                            text = confirmationText,
                            onTextChange = { confirmationText = it },
                            pattern = confirmationPattern,
                            onPatternChange = { confirmationPattern = it },
                        )
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) CircularProgressIndicator()
                Button(enabled = !busy, onClick = ::perform) {
                    Text(
                        when (mode) {
                            CredentialFlowMode.ENROLL -> stringResource(R.string.credential_save)
                            CredentialFlowMode.VERIFY -> stringResource(R.string.credential_verify)
                            CredentialFlowMode.CHANGE -> stringResource(R.string.credential_change)
                        },
                    )
                }
                OutlinedButton(enabled = !busy, onClick = onBack) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun CredentialEntry(
    type: PrimaryCredentialType,
    label: String,
    text: String,
    onTextChange: (String) -> Unit,
    pattern: List<Int>,
    onPatternChange: (List<Int>) -> Unit,
) {
    if (type == PrimaryCredentialType.PATTERN) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        PatternInput(pattern, onPatternChange)
        Text(stringResource(R.string.pattern_instructions), style = MaterialTheme.typography.bodySmall)
    } else {
        val maxLength = if (type == PrimaryCredentialType.PIN) {
            CredentialRules.PIN_MAX_LENGTH
        } else {
            CredentialRules.PASSWORD_MAX_LENGTH
        }
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().semantics { password() },
            value = text,
            onValueChange = { if (it.length <= maxLength) onTextChange(it) },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (type == PrimaryCredentialType.PIN) KeyboardType.NumberPassword else KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
        )
    }
}

private fun toCredentialChars(
    type: PrimaryCredentialType,
    text: String,
    pattern: List<Int>,
): CharArray = if (type == PrimaryCredentialType.PATTERN) {
    val points = pattern.toIntArray()
    try {
        PatternCanonicalizer.canonicalize(points)
    } finally {
        points.fill(0)
    }
} else {
    text.toCharArray()
}

private fun rejectionMessage(reason: CredentialRejection): String = when (reason) {
    CredentialRejection.TOO_SHORT -> "The credential is too short."
    CredentialRejection.TOO_LONG -> "The credential is too long."
    CredentialRejection.INVALID_FORMAT -> "The credential format is invalid."
    CredentialRejection.TOO_WEAK -> "Choose a less common PIN."
    CredentialRejection.PATTERN_TOO_SHORT -> "Connect at least four pattern points."
    CredentialRejection.INVALID_PATTERN -> "That pattern is invalid. Draw a new pattern."
}
