package com.ashishkumar.nivara.data.biometrics

import androidx.biometric.BiometricPrompt
import com.ashishkumar.nivara.domain.biometrics.BiometricAvailability
import com.ashishkumar.nivara.domain.biometrics.BiometricPlatformResult

/** Pure translation of AndroidX callback error codes; it never tries to clear or bypass system lockout. */
internal fun mapBiometricPromptError(code: Int): BiometricPlatformResult = when (code) {
    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> BiometricPlatformResult.PrimaryCredentialRequired
    BiometricPrompt.ERROR_CANCELED,
    BiometricPrompt.ERROR_USER_CANCELED -> BiometricPlatformResult.UserCancelled
    BiometricPrompt.ERROR_LOCKOUT,
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricPlatformResult.SystemLockedOut
    BiometricPrompt.ERROR_HW_NOT_PRESENT ->
        BiometricPlatformResult.Unavailable(BiometricAvailability.NO_HARDWARE)
    BiometricPrompt.ERROR_NO_BIOMETRICS ->
        BiometricPlatformResult.Unavailable(BiometricAvailability.NO_ENROLLED_BIOMETRICS)
    BiometricPrompt.ERROR_HW_UNAVAILABLE ->
        BiometricPlatformResult.Unavailable(BiometricAvailability.HARDWARE_UNAVAILABLE)
    BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED ->
        BiometricPlatformResult.Unavailable(BiometricAvailability.SECURITY_UPDATE_REQUIRED)
    BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
    BiometricPrompt.ERROR_TIMEOUT,
    BiometricPrompt.ERROR_VENDOR -> BiometricPlatformResult.Unavailable(BiometricAvailability.UNKNOWN)
    else -> BiometricPlatformResult.SystemError
}
