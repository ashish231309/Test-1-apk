package com.ashishkumar.nivara.domain.vault

/** Opaque, process-memory-only handle for a prepared recovery-code setup. */
@JvmInline
value class VaultRecoverySetupId(val value: String) {
    init { require(value.matches(Regex("[0-9a-f]{32}"))) }
}

/** A prepared code has not been committed to external storage yet. Its String must be dropped after confirmation. */
class VaultRecoverySetupPreview(
    val setupId: VaultRecoverySetupId,
    val recoveryCode: String,
) {
    override fun toString(): String = "VaultRecoverySetupPreview(setupId=$setupId, recoveryCode=redacted)"
}

sealed interface VaultRecoverySetupResult {
    data class Prepared(val preview: VaultRecoverySetupPreview) : VaultRecoverySetupResult
    data object AlreadyConfigured : VaultRecoverySetupResult
    data object VaultUnavailable : VaultRecoverySetupResult
    data object VaultNotReady : VaultRecoverySetupResult
    data object Failed : VaultRecoverySetupResult
}

sealed interface VaultRecoverySetupCommitResult {
    data object Committed : VaultRecoverySetupCommitResult
    data object Expired : VaultRecoverySetupCommitResult
    data object AlreadyConfigured : VaultRecoverySetupCommitResult
    data object VaultUnavailable : VaultRecoverySetupCommitResult
    data object VerificationFailed : VaultRecoverySetupCommitResult
    data object Failed : VaultRecoverySetupCommitResult
}

enum class VaultRecoveryFailure {
    INVALID_MATERIAL,
    LOCATION_UNAVAILABLE,
    ACCESS_DENIED,
    NOT_A_VAULT,
    WRONG_VAULT,
    IDENTITY_UNKNOWN,
    RECOVERY_NOT_CONFIGURED,
    VAULT_DAMAGED,
    VAULT_UNSUPPORTED,
    RECORDS_DAMAGED,
    KEY_ACCESS_PERSISTENCE,
    UNAVAILABLE,
}

sealed interface VaultRecoveryResult {
    data class Reconnected(val vaultId: VaultId) : VaultRecoveryResult
    data class Failed(val reason: VaultRecoveryFailure) : VaultRecoveryResult
}

/**
 * Explicit recovery boundary. Preparing setup keeps only the encrypted wrapper in process memory; the code is
 * shown to the user but is not persisted. External write occurs only after the user confirms they saved it.
 */
interface VaultRecoveryRepository {
    suspend fun prepareSetup(vaultId: VaultId): VaultRecoverySetupResult
    suspend fun confirmSetup(setupId: VaultRecoverySetupId): VaultRecoverySetupCommitResult
    suspend fun cancelSetup(setupId: VaultRecoverySetupId)

    /** Consumes and clears the supplied character array; recovery does not establish an application session. */
    suspend fun recover(recoveryCode: CharArray): VaultRecoveryResult
}
