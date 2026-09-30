package com.ashishkumar.nivara.data.biometrics

import androidx.biometric.BiometricManager
import com.ashishkumar.nivara.domain.biometrics.BiometricAvailability

/** Pure mapping of AndroidX BiometricManager capability codes into the Android-free domain contract. */
internal fun mapBiometricManagerAvailability(code: Int): BiometricAvailability = when (code) {
    BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NO_HARDWARE
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.HARDWARE_UNAVAILABLE
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NO_ENROLLED_BIOMETRICS
    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> BiometricAvailability.UNSUPPORTED
    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricAvailability.SECURITY_UPDATE_REQUIRED
    else -> BiometricAvailability.UNKNOWN
}
