package com.ashishkumar.nivara.domain.vault

/** Opaque non-secret identifier for one initialized vault; never contains a path or URI. */
@JvmInline
value class VaultId(val value: String) {
    init {
        require(value.matches(Regex("[0-9a-f]{32}")))
    }

    internal fun toBytes(): ByteArray = ByteArray(16) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): VaultId {
            require(bytes.size == 16)
            return VaultId(bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        }
    }
}

/** Explicit, non-empty-aware state model for the selected external root. */
sealed interface VaultStatus {
    data object RootNotSelected : VaultStatus
    data object NotInitialized : VaultStatus
    data class Ready(val vaultId: VaultId) : VaultStatus
    data class Unavailable(val reason: VaultUnavailableReason) : VaultStatus
    data object AccessDenied : VaultStatus
    data object CorruptMetadata : VaultStatus
    /** A structurally recognized recovery slot exists, but this installation cannot unwrap its local wrapper. */
    data class RecoveryRequired(val vaultId: VaultId) : VaultStatus
    /** The chosen location is not an initialized Nivara vault; it must never be initialized implicitly. */
    data object NotAVault : VaultStatus
    data class UnsupportedVersion(val component: VaultFormatComponent, val version: Int?) : VaultStatus
    data object InvalidStructure : VaultStatus
    data class InitializationFailed(val reason: VaultInitializationFailure) : VaultStatus
}

enum class VaultUnavailableReason {
    EXTERNAL_STORAGE,
    DEVICE_KEY_MISSING,
    DEVICE_KEY_INVALIDATED,
    CRYPTOGRAPHIC_SERVICE,
}

enum class VaultFormatComponent { METADATA, KEY_ENVELOPE, ENCRYPTED_HEADER, RECOVERY }

enum class VaultInitializationFailure {
    CRYPTOGRAPHIC_OPERATION,
    DIRECTORY_CREATION,
    METADATA_WRITE,
    ATOMIC_COMMIT,
    STORAGE_COMMIT,
    STORAGE_CLEANUP,
    POST_WRITE_VERIFICATION,
}

sealed interface VaultInitializationResult {
    data class Initialized(val vaultId: VaultId) : VaultInitializationResult
    data object AlreadyInitialized : VaultInitializationResult
    data object RootNotSelected : VaultInitializationResult
    data class Unavailable(val reason: VaultUnavailableReason) : VaultInitializationResult
    data object AccessDenied : VaultInitializationResult
    data object CorruptMetadata : VaultInitializationResult
    data class UnsupportedVersion(val component: VaultFormatComponent, val version: Int?) : VaultInitializationResult
    data object InvalidStructure : VaultInitializationResult
    data class Failed(val reason: VaultInitializationFailure) : VaultInitializationResult
}

/** Picker results contain no platform storage handle or path. */
enum class VaultRootSelectionResult {
    SELECTED,
    RECONNECTED,
    CANCELLED,
    INVALID_SELECTION,
    DIFFERENT_ROOT_ALREADY_SELECTED,
    ACCESS_DENIED,
    UNAVAILABLE,
    PERSISTENCE_FAILURE,
}

sealed interface VaultMetadataFile {
    data object Missing : VaultMetadataFile
    data class Present(val bytes: ByteArray) : VaultMetadataFile
    data object Unreadable : VaultMetadataFile
    data object AccessDenied : VaultMetadataFile
    data object Unavailable : VaultMetadataFile
    data object WrongType : VaultMetadataFile
}

enum class VaultDirectoryEntry { MISSING, DIRECTORY, WRONG_TYPE }

sealed interface VaultStorageSnapshot {
    data object RootNotSelected : VaultStorageSnapshot
    data object AccessDenied : VaultStorageSnapshot
    data object Unavailable : VaultStorageSnapshot
    data class Available(
        val metadata: VaultMetadataFile,
        val dataDirectory: VaultDirectoryEntry,
        val unexpectedEntries: Boolean,
        val unexpectedDataEntries: Boolean = false,
        val recoveryRecord: VaultRecoveryFile = VaultRecoveryFile.Missing,
    ) : VaultStorageSnapshot
}

sealed interface VaultStorageCommitResult {
    data object Created : VaultStorageCommitResult
    data object RootNotEmpty : VaultStorageCommitResult
    data object AccessDenied : VaultStorageCommitResult
    data object Unavailable : VaultStorageCommitResult
    data object DirectoryCreationFailed : VaultStorageCommitResult
    data object MetadataWriteFailed : VaultStorageCommitResult
    data object AtomicCommitFailed : VaultStorageCommitResult
    data object VerificationFailed : VaultStorageCommitResult
    data object Failed : VaultStorageCommitResult
    data object CleanupFailed : VaultStorageCommitResult
    data object RecoveryRecordExists : VaultStorageCommitResult
}

sealed interface VaultRecoveryFile {
    data object Missing : VaultRecoveryFile
    data class Present(val bytes: ByteArray) : VaultRecoveryFile
    data object Pending : VaultRecoveryFile
    data object Unreadable : VaultRecoveryFile
    data object AccessDenied : VaultRecoveryFile
    data object Unavailable : VaultRecoveryFile
    data object WrongType : VaultRecoveryFile
}

/** Platform storage port. It exposes bytes and typed outcomes, never Android storage handles. */
interface VaultStorage {
    suspend fun inspect(): VaultStorageSnapshot

    /** Creates the reserved data directory and commits metadata through a temporary sibling + rename. */
    suspend fun initializeAtomically(metadataBytes: ByteArray): VaultStorageCommitResult

    /** Adds a recovery wrapper to an already initialized vault without changing its key or base metadata. */
    suspend fun commitRecoveryRecordAtomically(
        expectedVaultId: VaultId,
        recordBytes: ByteArray,
    ): VaultStorageCommitResult = VaultStorageCommitResult.Failed
}

interface VaultRepository {
    suspend fun inspect(): VaultStatus
    suspend fun initialize(): VaultInitializationResult
}
