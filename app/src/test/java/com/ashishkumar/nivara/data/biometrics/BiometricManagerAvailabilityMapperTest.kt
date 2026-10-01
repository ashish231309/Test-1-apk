package com.ashishkumar.nivara.data.biometrics

import androidx.biometric.BiometricManager
import com.ashishkumar.nivara.domain.biometrics.BiometricAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class BiometricManagerAvailabilityMapperTest {
    @Test
    fun mapsCapabilityAndEnrollmentStates() {
        assertEquals(
            BiometricAvailability.AVAILABLE,
            mapBiometricManagerAvailability(BiometricManager.BIOMETRIC_SUCCESS),
        )
        assertEquals(
            BiometricAvailability.NO_HARDWARE,
            mapBiometricManagerAvailability(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
        )
        assertEquals(
            BiometricAvailability.HARDWARE_UNAVAILABLE,
            mapBiometricManagerAvailability(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE),
        )
        assertEquals(
            BiometricAvailability.NO_ENROLLED_BIOMETRICS,
            mapBiometricManagerAvailability(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED),
        )
        assertEquals(
            BiometricAvailability.UNSUPPORTED,
            mapBiometricManagerAvailability(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED),
        )
        assertEquals(
            BiometricAvailability.SECURITY_UPDATE_REQUIRED,
            mapBiometricManagerAvailability(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED),
        )
    }

    @Test
    fun unknownCapabilityCodesFailClosed() {
        assertEquals(BiometricAvailability.UNKNOWN, mapBiometricManagerAvailability(Int.MIN_VALUE))
    }
}
