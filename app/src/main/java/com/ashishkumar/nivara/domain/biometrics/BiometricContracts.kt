package com.ashishkumar.nivara.domain.biometrics

import com.ashishkumar.nivara.domain.credentials.AuthenticationResult

/** Normalized availability reported by AndroidX Biometric; contains no Android types. */
enum class BiometricAvailability {
    AVAILABLE,
    NO_HARDWARE,
    HARDWARE_UNAVAILABLE,
    NO_ENROLLED_BIOMETRICS,
    UNSUPPORTED,
    SECURITY_UPDATE_REQUIRED,
    UNKNOWN,
}

enum class BiometricUnavailableReason {
    NO_HARDWARE,
    HARDWARE_UNAVAILABLE,
    NO_ENROLLED_BIOMETRICS,
    UNSUPPORTED,
    SECURITY_UPDATE_REQUIRED,
    PRIMARY_CREDENTIAL_REQUIRED,
    PRIMARY_CREDENTIAL_UNAVAILABLE,
    UNKNOWN,
}

sealed interface BiometricStatus {
    data class Disabled(val availability: BiometricAvailability) : BiometricStatus
    data object Enabled : BiometricStatus
    data class Invalidated(val availability: BiometricAvailability) : BiometricStatus
    data class Unavailable(val reason: BiometricUnavailableReason) : BiometricStatus
    data class TemporarilyBlocked(val retryAfterMillis: Long) : BiometricStatus
}

sealed interface BiometricAuthenticationResult {
    data object Authenticated : BiometricAuthenticationResult
    data object Failed : BiometricAuthenticationResult
    data object UserCancelled : BiometricAuthenticationResult
    /** AndroidX negative button was selected; the UI should offer the primary credential. */
    data object PrimaryCredentialRequired : BiometricAuthenticationResult
    data class TemporarilyBlocked(val retryAfterMillis: Long) : BiometricAuthenticationResult
    data object SystemLockedOut : BiometricAuthenticationResult
    data class Unavailable(val reason: BiometricUnavailableReason) : BiometricAuthenticationResult
    data object Invalidated : BiometricAuthenticationResult
    data object SystemError : BiometricAuthenticationResult
    data object NotEnabled : BiometricAuthenticationResult
    data object PersistenceFailure : BiometricAuthenticationResult
}

sealed interface BiometricManagementResult {
    data object Enabled : BiometricManagementResult
    data object Disabled : BiometricManagementResult
    data object AlreadyEnabled : BiometricManagementResult
    data object AlreadyDisabled : BiometricManagementResult
    data object PrimaryCredentialRequired : BiometricManagementResult
    data object PrimaryAuthenticationFailed : BiometricManagementResult
    data class PrimaryTemporarilyBlocked(val retryAfterMillis: Long) : BiometricManagementResult
    data class Unavailable(val reason: BiometricUnavailableReason) : BiometricManagementResult
    data object UserCancelled : BiometricManagementResult
    data class TemporarilyBlocked(val retryAfterMillis: Long) : BiometricManagementResult
    data object SystemLockedOut : BiometricManagementResult
    data object Invalidated : BiometricManagementResult
    data object PersistenceFailure : BiometricManagementResult
    data object SystemError : BiometricManagementResult
}

/** Persisted state is metadata only. No biometric template, key bytes, or biometric payload is stored. */
enum class BiometricRecordState { DISABLED, ENABLED, INVALIDATED }

data class BiometricAttemptState(
    val failedAttempts: Int = 0,
    val blockedUntilEpochMillis: Long = 0,
) {
    init {
        require(failedAttempts >= 0)
        require(blockedUntilEpochMillis >= 0)
    }
}

class BiometricPersistenceFailure : Exception("Biometric configuration is unavailable.")

interface BiometricStateStore {
    suspend fun recordState(): BiometricRecordState
    suspend fun setState(state: BiometricRecordState)
    suspend fun attempts(): BiometricAttemptState
    suspend fun recordFailure(nowEpochMillis: Long): BiometricAttemptState
    suspend fun resetAttempts()
}

/** A thin Android-free boundary over BiometricPrompt, BiometricManager and the biometric-bound Keystore key. */
interface BiometricPromptPlatform {
    fun availability(): BiometricAvailability

    /**
     * Create a new key only for an explicit enable flow; authentication must never silently recreate a key.
     * The callback is invoked for each failed match. Return false to cancel the prompt at the app's limit.
     */
    suspend fun authenticate(
        createKey: Boolean,
        continueAfterFailure: suspend () -> Boolean,
    ): BiometricPlatformResult

    suspend fun deleteKey()
}

sealed interface BiometricPlatformResult {
    data object Authenticated : BiometricPlatformResult
    data object ApplicationThrottled : BiometricPlatformResult
    data object UserCancelled : BiometricPlatformResult
    data object PrimaryCredentialRequired : BiometricPlatformResult
    data object SystemLockedOut : BiometricPlatformResult
    data class Unavailable(val availability: BiometricAvailability) : BiometricPlatformResult
    data object Invalidated : BiometricPlatformResult
    data object SystemError : BiometricPlatformResult
}

/**
 * Stage 4 application boundary. Primary authentication remains authoritative for setup/disable and fallback;
 * biometric success is an ephemeral secondary authentication result, not a session.
 */
interface BiometricAuthenticator {
    suspend fun status(): BiometricStatus
    suspend fun authenticate(): BiometricAuthenticationResult
    suspend fun authenticatePrimaryFallback(credential: CharArray): AuthenticationResult
    suspend fun enable(primaryCredential: CharArray): BiometricManagementResult
    suspend fun disable(primaryCredential: CharArray): BiometricManagementResult

    /** Called only after another primary-authenticated operation (for example, credential replacement) succeeds. */
    suspend fun recordPrimaryAuthenticationSuccess()
}
