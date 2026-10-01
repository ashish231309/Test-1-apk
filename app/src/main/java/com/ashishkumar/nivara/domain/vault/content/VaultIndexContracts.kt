package com.ashishkumar.nivara.domain.vault.content

import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.VaultStatus
import java.io.InputStream
import java.io.OutputStream

sealed interface VaultIndexRead {
    data object Missing : VaultIndexRead
    data class Ready(
        val generation: Long,
        val items: List<VaultItem>,
        val contentDiagnostics: VaultContentDiagnostics? = null,
    ) : VaultIndexRead
    data object Corrupt : VaultIndexRead
    data class UnsupportedVersion(val version: Int) : VaultIndexRead
    data object Unavailable : VaultIndexRead
    data object AccessDenied : VaultIndexRead
    data object ObjectsWithoutIndex : VaultIndexRead
    data class ContentWithoutIndex(val unindexedObjects: Int, val unfinishedObjects: Int) : VaultIndexRead {
        init { require(unindexedObjects >= 0 && unfinishedObjects >= 0) }
    }
    data class VaultUnavailable(val status: VaultStatus) : VaultIndexRead
}

sealed interface VaultIndexInitializationResult {
    data object Initialized : VaultIndexInitializationResult
    data object AlreadyInitialized : VaultIndexInitializationResult
    data object ObjectsAlreadyPresent : VaultIndexInitializationResult
    data object Corrupt : VaultIndexInitializationResult
    data object UnsupportedVersion : VaultIndexInitializationResult
    data object Unavailable : VaultIndexInitializationResult
    data object AccessDenied : VaultIndexInitializationResult
    data class VaultUnavailable(val status: VaultStatus) : VaultIndexInitializationResult
    data object WriteFailed : VaultIndexInitializationResult
    data object AuthorizationExpired : VaultIndexInitializationResult
}

sealed interface VaultIndexWriteResult {
    data class Added(val item: VaultItem, val generation: Long, val oldGenerationsPruned: Boolean = true) : VaultIndexWriteResult
    data object DuplicateId : VaultIndexWriteResult
    data object MissingIndex : VaultIndexWriteResult
    data object Corrupt : VaultIndexWriteResult
    data class UnsupportedVersion(val version: Int) : VaultIndexWriteResult
    data object Unavailable : VaultIndexWriteResult
    data object AccessDenied : VaultIndexWriteResult
    data object Failed : VaultIndexWriteResult
    data object AuthorizationExpired : VaultIndexWriteResult
    data class VaultUnavailable(val status: VaultStatus) : VaultIndexWriteResult
}

sealed interface VaultIndexFiles {
    data class NoIndex(
        val hasPendingWrite: Boolean,
        val hasUnexpectedEntries: Boolean,
        val hasObjects: Boolean,
    ) : VaultIndexFiles
    data class Present(
        val generations: List<Long>,
        val hasPendingWrite: Boolean,
        val hasUnexpectedEntries: Boolean,
        val hasObjects: Boolean,
    ) : VaultIndexFiles
    data object Unavailable : VaultIndexFiles
    data object AccessDenied : VaultIndexFiles
}

data class VaultContentDiagnostics(
    val missingContent: Int,
    val unindexedObjects: Int,
    val unfinishedObjects: Int,
    val missingItemIds: Set<VaultItemId> = emptySet(),
) {
    init {
        require(missingContent >= 0 && unindexedObjects >= 0 && unfinishedObjects >= 0)
        require(missingItemIds.size <= missingContent)
    }
}

sealed interface VaultObjectDirectoryRead {
    data class Available(
        val objectIds: Set<VaultItemId>,
        val unfinishedObjects: Int,
        val unrecognizedEntries: Int,
    ) : VaultObjectDirectoryRead
    data object Unavailable : VaultObjectDirectoryRead
    data object AccessDenied : VaultObjectDirectoryRead
}

/** Organization storage state, independent from the authenticated item-index failure domain. */
sealed interface VaultOrganizationFiles {
    data object Missing : VaultOrganizationFiles
    data class Present(
        val generations: List<Long>,
        val hasPendingWrite: Boolean,
        val hasUnexpectedEntries: Boolean,
    ) : VaultOrganizationFiles
    data object Unavailable : VaultOrganizationFiles
    data object AccessDenied : VaultOrganizationFiles
}

sealed interface VaultOrganizationStorageResult {
    data object Created : VaultOrganizationStorageResult
    data object Conflict : VaultOrganizationStorageResult
    data object Unavailable : VaultOrganizationStorageResult
    data object AccessDenied : VaultOrganizationStorageResult
    data object WriteFailed : VaultOrganizationStorageResult
    data object VerificationFailed : VaultOrganizationStorageResult
    data object CleanupFailed : VaultOrganizationStorageResult
    data object AuthorizationExpired : VaultOrganizationStorageResult
}

sealed interface VaultIndexFileCommitResult {
    data object Created : VaultIndexFileCommitResult
    data object Conflict : VaultIndexFileCommitResult
    data object Unavailable : VaultIndexFileCommitResult
    data object AccessDenied : VaultIndexFileCommitResult
    data object WriteFailed : VaultIndexFileCommitResult
    data object VerificationFailed : VaultIndexFileCommitResult
    data object AuthorizationExpired : VaultIndexFileCommitResult
}

sealed interface VaultObjectCommitResult {
    data class Created(val objectSizeBytes: Long) : VaultObjectCommitResult
    data object Conflict : VaultObjectCommitResult
    data object SourceUnavailable : VaultObjectCommitResult
    data object DestinationUnavailable : VaultObjectCommitResult
    data object AccessDenied : VaultObjectCommitResult
    data object WriteFailed : VaultObjectCommitResult
    data object VerificationFailed : VaultObjectCommitResult
    data object CleanupFailed : VaultObjectCommitResult
    data object AuthorizationExpired : VaultObjectCommitResult
}

data class VaultObjectWriteInfo(val plaintextBytes: Long, val expectedObjectBytes: Long) {
    init {
        require(plaintextBytes >= 0)
        require(expectedObjectBytes >= 0)
    }
}

/** SAF-free port shared by the authenticated index and object store. */
interface VaultContentStorage {
    suspend fun initializeContentDirectories(): VaultIndexFileCommitResult
    suspend fun inspectIndexFiles(): VaultIndexFiles
    suspend fun readIndexFile(generation: Long): ByteArray?
    suspend fun commitIndexFileAtomically(
        generation: Long,
        bytes: ByteArray,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultIndexFileCommitResult
    /** Prune only after the repository has authenticated and compared the just-written generation. */
    suspend fun pruneIndexFiles(
        keepGenerations: Set<Long>,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultIndexFileCommitResult = VaultIndexFileCommitResult.Unavailable
    /** Roll back only an unverified generation created by the immediately preceding mutation. */
    suspend fun discardIndexFile(generation: Long): VaultIndexFileCommitResult = VaultIndexFileCommitResult.Unavailable
    suspend fun writeObjectAtomically(
        itemId: VaultItemId,
        authorizationCheckpoint: suspend () -> Boolean,
        writer: suspend (OutputStream) -> VaultObjectWriteInfo,
    ): VaultObjectCommitResult
    suspend fun openObject(itemId: VaultItemId): InputStream?
    suspend fun openObjectResult(itemId: VaultItemId): VaultObjectOpenResult =
        openObject(itemId)?.let(VaultObjectOpenResult::Opened) ?: VaultObjectOpenResult.Missing
    suspend fun inspectObjectDirectory(): VaultObjectDirectoryRead = VaultObjectDirectoryRead.Unavailable

    suspend fun inspectOrganizationFiles(): VaultOrganizationFiles = VaultOrganizationFiles.Unavailable
    suspend fun readOrganizationFile(generation: Long): ByteArray? = null
    suspend fun commitOrganizationFileAtomically(
        generation: Long,
        bytes: ByteArray,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultOrganizationStorageResult = VaultOrganizationStorageResult.Unavailable
    /** Called only after the repository has authenticated and decoded the newly committed generation. */
    suspend fun pruneOrganizationFiles(
        keepGenerations: Set<Long>,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultOrganizationStorageResult = VaultOrganizationStorageResult.Unavailable
    /** Roll back only a just-created, not-yet-verified generation; retained committed generations are untouched. */
    suspend fun discardOrganizationFile(generation: Long): VaultOrganizationStorageResult =
        VaultOrganizationStorageResult.Unavailable
}

/** The only bridge from Stage 13's vault content key to authenticated item and organization records; no key object/bytes leave the crypto service. */
interface VaultContentCrypto {
    suspend fun encryptIndex(vaultId: VaultId, generation: Long, plaintext: ByteArray): VaultContentCryptoResult<ByteArray>
    suspend fun decryptIndex(vaultId: VaultId, generation: Long, encoded: ByteArray): VaultContentCryptoResult<ByteArray>
    /** Uses the existing vault content key with a distinct organization-record purpose/context. */
    suspend fun encryptOrganization(
        vaultId: VaultId,
        generation: Long,
        plaintext: ByteArray,
    ): VaultContentCryptoResult<ByteArray> = VaultContentCryptoResult.OperationFailed
    suspend fun decryptOrganization(
        vaultId: VaultId,
        generation: Long,
        encoded: ByteArray,
    ): VaultContentCryptoResult<ByteArray> = VaultContentCryptoResult.OperationFailed
    suspend fun encryptObject(
        vaultId: VaultId,
        itemId: VaultItemId,
        source: InputStream,
        destination: OutputStream,
        expectedSourceSize: Long?,
        authorizationCheckpoint: suspend () -> Boolean,
        onProgress: (Long) -> Unit,
    ): VaultContentCryptoResult<VaultContentEncryptionResult>
    suspend fun verifyObject(
        vaultId: VaultId,
        item: VaultItem,
        source: InputStream,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultContentCryptoResult<Boolean>
    /** Writes unauthenticated streaming output only to a private quarantine sink; publish it after Success. */
    suspend fun decryptObjectToQuarantine(
        vaultId: VaultId,
        item: VaultItem,
        source: InputStream,
        destination: OutputStream,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultContentCryptoResult<VaultContentReadSummary> = VaultContentCryptoResult.OperationFailed
}

data class VaultContentReadSummary(val plaintextBytes: Long, val objectBytes: Long)

class VaultContentEncryptionResult(
    val plaintextBytes: Long,
    val objectBytes: Long,
    encryptedItemKey: ByteArray,
) {
    private val encryptedKeyBytes = encryptedItemKey.copyOf()
    val encryptedItemKey: ByteArray get() = encryptedKeyBytes.copyOf()
    fun clear() { encryptedKeyBytes.fill(0) }
}

sealed interface VaultContentCryptoResult<out T> {
    data class Success<T>(val value: T) : VaultContentCryptoResult<T>
    data class VaultUnavailable(val status: VaultStatus) : VaultContentCryptoResult<Nothing>
    data object AuthenticationFailed : VaultContentCryptoResult<Nothing>
    data class UnsupportedVersion(val version: Int?) : VaultContentCryptoResult<Nothing>
    data object OperationFailed : VaultContentCryptoResult<Nothing>
    data object AuthorizationExpired : VaultContentCryptoResult<Nothing>
    data object SourceSizeMismatch : VaultContentCryptoResult<Nothing>
}

sealed interface VaultImportResult {
    data class Imported(val item: VaultItem, val count: Int) : VaultImportResult
    data object SourceUnavailable : VaultImportResult
    data object SourceAccessDenied : VaultImportResult
    data object SourceMetadataInvalid : VaultImportResult
    data object SourceSizeMismatch : VaultImportResult
    data object DestinationUnavailable : VaultImportResult
    data object DestinationAccessDenied : VaultImportResult
    data object VaultUnavailable : VaultImportResult
    data object IndexMissing : VaultImportResult
    data object IndexUnreadable : VaultImportResult
    data object IndexUnsupported : VaultImportResult
    data object IndexWriteFailed : VaultImportResult
    data object DuplicateId : VaultImportResult
    data object EncryptionFailed : VaultImportResult
    data object AuthorizationExpired : VaultImportResult
    data object Failed : VaultImportResult
}

interface VaultIndexRepository {
    suspend fun inspect(vaultId: VaultId): VaultIndexRead
    suspend fun initializeEmpty(
        vaultId: VaultId,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultIndexInitializationResult
    suspend fun listItems(vaultId: VaultId): VaultIndexRead
    suspend fun addItem(
        vaultId: VaultId,
        item: VaultItem,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultIndexWriteResult
    suspend fun moveToTrash(
        vaultId: VaultId,
        itemId: VaultItemId,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultItemStateMutationResult = VaultItemStateMutationResult.WriteFailed
    suspend fun restoreFromTrash(
        vaultId: VaultId,
        itemId: VaultItemId,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultItemStateMutationResult = VaultItemStateMutationResult.WriteFailed
}

sealed interface VaultItemStateMutationResult {
    data class Changed(
        val item: VaultItem,
        val generation: Long,
        val items: List<VaultItem>,
        val oldGenerationsPruned: Boolean,
    ) : VaultItemStateMutationResult
    data object AlreadyActive : VaultItemStateMutationResult
    data object AlreadyTrashed : VaultItemStateMutationResult
    data object ItemNotFound : VaultItemStateMutationResult
    data object MissingIndex : VaultItemStateMutationResult
    data object Corrupt : VaultItemStateMutationResult
    data class UnsupportedVersion(val version: Int) : VaultItemStateMutationResult
    data object Unavailable : VaultItemStateMutationResult
    data object AccessDenied : VaultItemStateMutationResult
    data class VaultUnavailable(val status: com.ashishkumar.nivara.domain.vault.VaultStatus) : VaultItemStateMutationResult
    data object WriteFailed : VaultItemStateMutationResult
    data object AuthorizationExpired : VaultItemStateMutationResult
}

interface VaultImportRepository {
    fun discard(sourceId: VaultSourceSelectionId)
    suspend fun importDocument(
        sourceId: VaultSourceSelectionId,
        vaultId: VaultId,
        authorizationCheckpoint: suspend () -> Boolean,
        onProgress: (Long, Long?) -> Unit,
    ): VaultImportResult
}

data class VaultSourceMetadata(val filename: String, val mimeType: String?, val sizeBytes: Long?)

sealed interface VaultSourceMetadataRead {
    data class Available(val metadata: VaultSourceMetadata) : VaultSourceMetadataRead
    data object Unavailable : VaultSourceMetadataRead
    data object AccessDenied : VaultSourceMetadataRead
}

sealed interface VaultSourceOpenResult {
    data class Opened(val stream: InputStream) : VaultSourceOpenResult
    data object Unavailable : VaultSourceOpenResult
    data object AccessDenied : VaultSourceOpenResult
}

interface VaultSourceDocumentProvider {
    suspend fun metadata(sourceId: VaultSourceSelectionId): VaultSourceMetadataRead
    suspend fun open(sourceId: VaultSourceSelectionId): VaultSourceOpenResult
    fun discard(sourceId: VaultSourceSelectionId)
}

@JvmInline
value class VaultSourceSelectionId(val value: String) {
    init { require(value.matches(Regex("[0-9a-f]{32}"))) }
}

sealed interface VaultSourceSelectionResult {
    data class Selected(val sourceId: VaultSourceSelectionId) : VaultSourceSelectionResult
    data object Cancelled : VaultSourceSelectionResult
    data object Unavailable : VaultSourceSelectionResult
    data object AccessDenied : VaultSourceSelectionResult
}
