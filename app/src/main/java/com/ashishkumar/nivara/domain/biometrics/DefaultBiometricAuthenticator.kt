package com.ashishkumar.nivara.domain.biometrics

import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.credentials.CredentialClock
import com.ashishkumar.nivara.domain.credentials.CredentialServiceStatus
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialService
import kotlinx.coroutines.CancellationException

/** Application policy and primary-credential gate. Platform prompt/key details remain in data. */
class DefaultBiometricAuthenticator(
    private val primaryCredentials: PrimaryCredentialService,
    private val platform: BiometricPromptPlatform,
    private val stateStore: BiometricStateStore,
    private val clock: CredentialClock,
) : BiometricAuthenticator {
    override suspend fun status(): BiometricStatus {
        return try {
            when (primaryCredentials.status()) {
                CredentialServiceStatus.NotConfigured ->
                    return BiometricStatus.Unavailable(BiometricUnavailableReason.PRIMARY_CREDENTIAL_REQUIRED)
                CredentialServiceStatus.InvalidConfiguration ->
                    return BiometricStatus.Unavailable(BiometricUnavailableReason.PRIMARY_CREDENTIAL_UNAVAILABLE)
                is CredentialServiceStatus.Configured -> Unit
            }
            val availability = platform.availability()
            when (stateStore.recordState()) {
                BiometricRecordState.DISABLED -> BiometricStatus.Disabled(availability)
                BiometricRecordState.INVALIDATED -> BiometricStatus.Invalidated(availability)
                BiometricRecordState.ENABLED -> {
                    val retryAfter = BiometricThrottlePolicy.retryAfter(stateStore.attempts(), clock.nowEpochMillis())
                    when {
                        retryAfter > 0 -> BiometricStatus.TemporarilyBlocked(retryAfter)
                        availability != BiometricAvailability.AVAILABLE ->
                            BiometricStatus.Unavailable(availability.reason())
                        else -> BiometricStatus.Enabled
                    }
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            BiometricStatus.Unavailable(BiometricUnavailableReason.UNKNOWN)
        }
    }

    override suspend fun authenticate(): BiometricAuthenticationResult {
        try {
            when (primaryCredentials.status()) {
                CredentialServiceStatus.NotConfigured -> return BiometricAuthenticationResult.Unavailable(
                    BiometricUnavailableReason.PRIMARY_CREDENTIAL_REQUIRED,
                )
                CredentialServiceStatus.InvalidConfiguration -> return BiometricAuthenticationResult.Unavailable(
                    BiometricUnavailableReason.PRIMARY_CREDENTIAL_UNAVAILABLE,
                )
                is CredentialServiceStatus.Configured -> Unit
            }
            when (stateStore.recordState()) {
                BiometricRecordState.DISABLED -> return BiometricAuthenticationResult.NotEnabled
                BiometricRecordState.INVALIDATED -> return BiometricAuthenticationResult.Invalidated
                BiometricRecordState.ENABLED -> Unit
            }
            val now = clock.nowEpochMillis().coerceAtLeast(0)
            val retryAfter = BiometricThrottlePolicy.retryAfter(stateStore.attempts(), now)
            if (retryAfter > 0) return BiometricAuthenticationResult.TemporarilyBlocked(retryAfter)

            val availability = platform.availability()
            if (availability != BiometricAvailability.AVAILABLE) {
                return BiometricAuthenticationResult.Unavailable(availability.reason())
            }

            var retryAfterOnThreshold = 0L
            val result = platform.authenticate(createKey = false) {
                val failureTime = clock.nowEpochMillis().coerceAtLeast(0)
                val state = stateStore.recordFailure(failureTime)
                retryAfterOnThreshold = BiometricThrottlePolicy.retryAfter(state, failureTime)
                retryAfterOnThreshold == 0L
            }
            return when (result) {
                BiometricPlatformResult.Authenticated -> {
                    stateStore.resetAttempts()
                    BiometricAuthenticationResult.Authenticated
                }
                BiometricPlatformResult.ApplicationThrottled ->
                    BiometricAuthenticationResult.TemporarilyBlocked(retryAfterOnThreshold.coerceAtLeast(1))
                BiometricPlatformResult.UserCancelled -> BiometricAuthenticationResult.UserCancelled
                BiometricPlatformResult.PrimaryCredentialRequired ->
                    BiometricAuthenticationResult.PrimaryCredentialRequired
                BiometricPlatformResult.SystemLockedOut -> BiometricAuthenticationResult.SystemLockedOut
                is BiometricPlatformResult.Unavailable ->
                    BiometricAuthenticationResult.Unavailable(result.availability.reason())
                BiometricPlatformResult.Invalidated -> {
                    stateStore.setState(BiometricRecordState.INVALIDATED)
                    stateStore.resetAttempts()
                    BiometricAuthenticationResult.Invalidated
                }
                BiometricPlatformResult.SystemError -> BiometricAuthenticationResult.SystemError
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: BiometricPersistenceFailure) {
            return BiometricAuthenticationResult.PersistenceFailure
        } catch (failure: Exception) {
            return BiometricAuthenticationResult.SystemError
        }
    }

    override suspend fun authenticatePrimaryFallback(credential: CharArray): AuthenticationResult {
        val result = try {
            primaryCredentials.authenticate(credential)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            return AuthenticationResult.InvalidConfiguration
        } finally {
            credential.fill('\u0000')
        }
        if (result is AuthenticationResult.Authenticated) {
            try {
                stateStore.resetAttempts()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // Primary authentication remains valid even if the independent biometric counter cannot reset.
            }
        }
        return result
    }

    override suspend fun enable(primaryCredential: CharArray): BiometricManagementResult {
        try {
            when (stateStore.recordState()) {
                BiometricRecordState.ENABLED -> return BiometricManagementResult.AlreadyEnabled
                BiometricRecordState.DISABLED, BiometricRecordState.INVALIDATED -> Unit
            }
            val primaryGate = verifyPrimaryCredential(primaryCredential)
            if (primaryGate != null) return primaryGate

            val availability = platform.availability()
            if (availability != BiometricAvailability.AVAILABLE) {
                return BiometricManagementResult.Unavailable(availability.reason())
            }
            var retryAfterOnThreshold = 0L
            return when (val result = platform.authenticate(createKey = true) {
                val failureTime = clock.nowEpochMillis().coerceAtLeast(0)
                val state = stateStore.recordFailure(failureTime)
                retryAfterOnThreshold = BiometricThrottlePolicy.retryAfter(state, failureTime)
                retryAfterOnThreshold == 0L
            }) {
                BiometricPlatformResult.Authenticated -> {
                    stateStore.setState(BiometricRecordState.ENABLED)
                    stateStore.resetAttempts()
                    BiometricManagementResult.Enabled
                }
                BiometricPlatformResult.ApplicationThrottled ->
                    BiometricManagementResult.TemporarilyBlocked(retryAfterOnThreshold.coerceAtLeast(1))
                BiometricPlatformResult.UserCancelled,
                BiometricPlatformResult.PrimaryCredentialRequired -> BiometricManagementResult.UserCancelled
                BiometricPlatformResult.SystemLockedOut -> BiometricManagementResult.SystemLockedOut
                is BiometricPlatformResult.Unavailable ->
                    BiometricManagementResult.Unavailable(result.availability.reason())
                BiometricPlatformResult.Invalidated -> {
                    stateStore.setState(BiometricRecordState.INVALIDATED)
                    BiometricManagementResult.Invalidated
                }
                BiometricPlatformResult.SystemError -> BiometricManagementResult.SystemError
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: BiometricPersistenceFailure) {
            return BiometricManagementResult.PersistenceFailure
        } catch (failure: Exception) {
            return BiometricManagementResult.SystemError
        } finally {
            primaryCredential.fill('\u0000')
        }
    }

    override suspend fun disable(primaryCredential: CharArray): BiometricManagementResult {
        try {
            when (stateStore.recordState()) {
                BiometricRecordState.DISABLED -> return BiometricManagementResult.AlreadyDisabled
                BiometricRecordState.ENABLED, BiometricRecordState.INVALIDATED -> Unit
            }
            val primaryGate = verifyPrimaryCredential(primaryCredential)
            if (primaryGate != null) return primaryGate

            platform.deleteKey()
            stateStore.setState(BiometricRecordState.DISABLED)
            stateStore.resetAttempts()
            return BiometricManagementResult.Disabled
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: BiometricPersistenceFailure) {
            return BiometricManagementResult.PersistenceFailure
        } catch (failure: Exception) {
            return BiometricManagementResult.SystemError
        } finally {
            primaryCredential.fill('\u0000')
        }
    }

    override suspend fun recordPrimaryAuthenticationSuccess() {
        stateStore.resetAttempts()
    }

    private suspend fun verifyPrimaryCredential(credential: CharArray): BiometricManagementResult? {
        when (primaryCredentials.status()) {
            CredentialServiceStatus.NotConfigured -> return BiometricManagementResult.PrimaryCredentialRequired
            CredentialServiceStatus.InvalidConfiguration -> return BiometricManagementResult.SystemError
            is CredentialServiceStatus.Configured -> Unit
        }
        return when (val result = primaryCredentials.authenticate(credential)) {
            is AuthenticationResult.Authenticated -> {
                stateStore.resetAttempts()
                null
            }
            AuthenticationResult.NotConfigured -> BiometricManagementResult.PrimaryCredentialRequired
            AuthenticationResult.Failed -> BiometricManagementResult.PrimaryAuthenticationFailed
            is AuthenticationResult.TemporarilyBlocked ->
                BiometricManagementResult.PrimaryTemporarilyBlocked(result.retryAfterMillis)
            AuthenticationResult.InvalidConfiguration -> BiometricManagementResult.SystemError
        }
    }
}

private fun BiometricAvailability.reason(): BiometricUnavailableReason = when (this) {
    BiometricAvailability.AVAILABLE -> BiometricUnavailableReason.UNKNOWN
    BiometricAvailability.NO_HARDWARE -> BiometricUnavailableReason.NO_HARDWARE
    BiometricAvailability.HARDWARE_UNAVAILABLE -> BiometricUnavailableReason.HARDWARE_UNAVAILABLE
    BiometricAvailability.NO_ENROLLED_BIOMETRICS -> BiometricUnavailableReason.NO_ENROLLED_BIOMETRICS
    BiometricAvailability.UNSUPPORTED -> BiometricUnavailableReason.UNSUPPORTED
    BiometricAvailability.SECURITY_UPDATE_REQUIRED -> BiometricUnavailableReason.SECURITY_UPDATE_REQUIRED
    BiometricAvailability.UNKNOWN -> BiometricUnavailableReason.UNKNOWN
}
