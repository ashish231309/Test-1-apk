package com.ashishkumar.nivara.domain.vault

/** Storage-independent outcomes from authenticated reads needed before a recovered key can be reconnected. */
enum class ExistingVaultRecordsValidation { VALID, DAMAGED, UNSUPPORTED, ACCESS_DENIED, UNAVAILABLE }

enum class RecoveryCryptographyFailure {
    NOT_READY,
    LOCATION_UNAVAILABLE,
    ACCESS_DENIED,
    NOT_A_VAULT,
    WRONG_VAULT,
    IDENTITY_UNKNOWN,
    RECOVERY_NOT_CONFIGURED,
    INVALID_MATERIAL,
    DAMAGED,
    UNSUPPORTED,
    RECORDS_DAMAGED,
    PERSISTENCE_FAILED,
    UNAVAILABLE,
}

sealed interface RecoveryWrapExistingKeyResult {
    data class Wrapped(val bytes: ByteArray) : RecoveryWrapExistingKeyResult
    data object NotReady : RecoveryWrapExistingKeyResult
    data object AlreadyConfigured : RecoveryWrapExistingKeyResult
    data object Unavailable : RecoveryWrapExistingKeyResult
    data object Failed : RecoveryWrapExistingKeyResult
}

sealed interface RecoveryReconnectKeyResult {
    data object Reconnected : RecoveryReconnectKeyResult
    data class Failed(val reason: RecoveryCryptographyFailure) : RecoveryReconnectKeyResult
}

/** Only this narrow port accepts transient recovery-key bytes; implementations must clear all copies. */
interface VaultRecoveryCryptography {
    suspend fun wrapExistingContentKey(
        vaultId: VaultId,
        recoveryKeyBytes: ByteArray,
    ): RecoveryWrapExistingKeyResult

    /** Authenticates the prepared sidecar against the current base metadata without persisting any local key. */
    suspend fun verifyPreparedRecoveryRecord(
        vaultId: VaultId,
        recoveryKeyBytes: ByteArray,
        metadataBytes: ByteArray,
        recoveryRecordBytes: ByteArray,
    ): Boolean

    suspend fun reconnectWithRecoveryKey(
        vaultId: VaultId,
        recoveryKeyBytes: ByteArray,
        validateExistingRecords: suspend () -> ExistingVaultRecordsValidation,
    ): RecoveryReconnectKeyResult
}
