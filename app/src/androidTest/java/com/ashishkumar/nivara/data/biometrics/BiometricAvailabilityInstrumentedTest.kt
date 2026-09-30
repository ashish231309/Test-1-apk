package com.ashishkumar.nivara.data.biometrics

import androidx.biometric.BiometricManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real AndroidX capability query only; interactive prompt/key use needs a human-enrolled device. */
@RunWith(AndroidJUnit4::class)
class BiometricAvailabilityInstrumentedTest {
    @Test
    fun platformReturnsARecognizedStrongBiometricAvailabilityCode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        assertTrue(
            result == BiometricManager.BIOMETRIC_SUCCESS ||
                result == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ||
                result == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ||
                result == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
                result == BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED ||
                result == BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ||
                result == -1,
        )
    }
}
