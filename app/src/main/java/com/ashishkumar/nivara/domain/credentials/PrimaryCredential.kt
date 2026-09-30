package com.ashishkumar.nivara.domain.credentials

import com.ashishkumar.nivara.domain.security.KdfParameters

/** Exactly one value is persisted as the application's active primary credential type. */
enum class PrimaryCredentialType(val storageValue: String, val verifierContextId: Byte) {
    PIN("pin", 1),
    PASSWORD("password", 2),
    PATTERN("pattern", 3);

    companion object {
        fun fromStorageValue(value: String): PrimaryCredentialType? = entries.firstOrNull {
            it.storageValue == value
        }
    }
}

/** Only KDF metadata, salt, and an authenticated wrapped random verifier are retained. */
class StoredPrimaryCredential(
    val type: PrimaryCredentialType,
    val kdf: KdfParameters,
    salt: ByteArray,
    wrappedVerifier: ByteArray,
) {
    private val saltBytes = salt.copyOf()
    private val verifierBytes = wrappedVerifier.copyOf()

    val salt: ByteArray get() = saltBytes.copyOf()
    val wrappedVerifier: ByteArray get() = verifierBytes.copyOf()
}

class CredentialPersistenceFailure : Exception("Credential configuration is unavailable.")

data class AttemptState(
    val failedAttempts: Int = 0,
    val blockedUntilEpochMillis: Long = 0,
) {
    init {
        require(failedAttempts >= 0)
        require(blockedUntilEpochMillis >= 0)
    }
}

sealed interface EnrollmentResult {
    data class Enrolled(val type: PrimaryCredentialType) : EnrollmentResult
    data object AlreadyConfigured : EnrollmentResult
    data object ConfirmationMismatch : EnrollmentResult
    data class Rejected(val reason: CredentialRejection) : EnrollmentResult
    data object StorageFailure : EnrollmentResult
}

sealed interface AuthenticationResult {
    data class Authenticated(val type: PrimaryCredentialType) : AuthenticationResult
    data object NotConfigured : AuthenticationResult
    data object Failed : AuthenticationResult
    data class TemporarilyBlocked(val retryAfterMillis: Long) : AuthenticationResult
    data object InvalidConfiguration : AuthenticationResult
}

sealed interface CredentialChangeResult {
    data class Changed(val type: PrimaryCredentialType) : CredentialChangeResult
    data object NotConfigured : CredentialChangeResult
    data object AuthenticationFailed : CredentialChangeResult
    data class TemporarilyBlocked(val retryAfterMillis: Long) : CredentialChangeResult
    data object ConfirmationMismatch : CredentialChangeResult
    data class Rejected(val reason: CredentialRejection) : CredentialChangeResult
    data object InvalidConfiguration : CredentialChangeResult
    data object StorageFailure : CredentialChangeResult
}

enum class CredentialRejection {
    TOO_SHORT,
    TOO_LONG,
    INVALID_FORMAT,
    TOO_WEAK,
    PATTERN_TOO_SHORT,
    INVALID_PATTERN,
}

sealed interface CredentialServiceStatus {
    data object NotConfigured : CredentialServiceStatus
    data class Configured(val type: PrimaryCredentialType) : CredentialServiceStatus
    data object InvalidConfiguration : CredentialServiceStatus
}

/** A single durable active record plus minimal throttling state; there is deliberately no reset/delete API. */
interface PrimaryCredentialStore {
    suspend fun load(): StoredPrimaryCredential?
    suspend fun installIfAbsent(credential: StoredPrimaryCredential): Boolean
    suspend fun replace(credential: StoredPrimaryCredential)
    suspend fun attempts(): AttemptState
    suspend fun recordFailure(nowEpochMillis: Long): AttemptState
    suspend fun resetAttempts()
}

fun interface CredentialClock {
    fun nowEpochMillis(): Long
}

interface PrimaryCredentialService {
    suspend fun status(): CredentialServiceStatus

    /** Consumes and clears both supplied character arrays before returning. */
    suspend fun enroll(
        type: PrimaryCredentialType,
        credential: CharArray,
        confirmation: CharArray,
    ): EnrollmentResult

    /** Consumes and clears the submitted characters; no verifier material is returned to UI code. */
    suspend fun authenticate(credential: CharArray): AuthenticationResult

    /** Authenticates the old value and atomically replaces the sole active type/configuration. */
    suspend fun changePrimary(
        currentCredential: CharArray,
        newType: PrimaryCredentialType,
        newCredential: CharArray,
        confirmation: CharArray,
    ): CredentialChangeResult
}
