package com.ashishkumar.nivara.data.biometrics

import androidx.biometric.BiometricPrompt
import com.ashishkumar.nivara.domain.biometrics.BiometricAvailability
import com.ashishkumar.nivara.domain.biometrics.BiometricPlatformResult
import org.junit.Assert.assertEquals
import org.junit.Test

class BiometricPromptErrorMapperTest {
    @Test
    fun userActionsRemainDistinct() {
        assertEquals(
            BiometricPlatformResult.PrimaryCredentialRequired,
            mapBiometricPromptError(BiometricPrompt.ERROR_NEGATIVE_BUTTON),
        )
        assertEquals(
            BiometricPlatformResult.UserCancelled,
            mapBiometricPromptError(BiometricPrompt.ERROR_USER_CANCELED),
        )
        assertEquals(
            BiometricPlatformResult.UserCancelled,
            mapBiometricPromptError(BiometricPrompt.ERROR_CANCELED),
        )
    }

    @Test
    fun systemLockoutsAreSurfacedWithoutApplicationResetOrBypass() {
        assertEquals(
            BiometricPlatformResult.SystemLockedOut,
            mapBiometricPromptError(BiometricPrompt.ERROR_LOCKOUT),
        )
        assertEquals(
            BiometricPlatformResult.SystemLockedOut,
            mapBiometricPromptError(BiometricPrompt.ERROR_LOCKOUT_PERMANENT),
        )
    }

    @Test
    fun platformAvailabilityErrorsStaySeparateFromLockout() {
        assertEquals(
            BiometricPlatformResult.Unavailable(BiometricAvailability.NO_HARDWARE),
            mapBiometricPromptError(BiometricPrompt.ERROR_HW_NOT_PRESENT),
        )
        assertEquals(
            BiometricPlatformResult.Unavailable(BiometricAvailability.NO_ENROLLED_BIOMETRICS),
            mapBiometricPromptError(BiometricPrompt.ERROR_NO_BIOMETRICS),
        )
        assertEquals(
            BiometricPlatformResult.Unavailable(BiometricAvailability.HARDWARE_UNAVAILABLE),
            mapBiometricPromptError(BiometricPrompt.ERROR_HW_UNAVAILABLE),
        )
        assertEquals(
            BiometricPlatformResult.Unavailable(BiometricAvailability.SECURITY_UPDATE_REQUIRED),
            mapBiometricPromptError(BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED),
        )
    }

    @Test
    fun transientAndUnknownSystemErrorsDoNotBecomeAuthenticationSuccess() {
        assertEquals(
            BiometricPlatformResult.Unavailable(BiometricAvailability.UNKNOWN),
            mapBiometricPromptError(BiometricPrompt.ERROR_TIMEOUT),
        )
        assertEquals(
            BiometricPlatformResult.SystemError,
            mapBiometricPromptError(Int.MIN_VALUE),
        )
    }
}
