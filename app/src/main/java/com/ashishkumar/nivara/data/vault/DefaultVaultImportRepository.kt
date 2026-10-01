package com.ashishkumar.nivara.data.vault

import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.content.VaultContentCrypto
import com.ashishkumar.nivara.domain.vault.content.VaultContentCryptoResult
import com.ashishkumar.nivara.domain.vault.content.VaultContentStorage
import com.ashishkumar.nivara.domain.vault.content.VaultImportRepository
import com.ashishkumar.nivara.domain.vault.content.VaultImportResult
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRead
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRepository
import com.ashishkumar.nivara.domain.vault.content.VaultIndexWriteResult
import com.ashishkumar.nivara.domain.vault.content.VaultItem
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import com.ashishkumar.nivara.domain.vault.content.VaultItemValidation
import com.ashishkumar.nivara.domain.vault.content.VaultObjectCommitResult
import com.ashishkumar.nivara.domain.vault.content.VaultObjectWriteInfo
import com.ashishkumar.nivara.domain.vault.content.VaultSourceDocumentProvider
import com.ashishkumar.nivara.domain.vault.content.VaultSourceMetadata
import com.ashishkumar.nivara.domain.vault.content.VaultSourceMetadataRead
import com.ashishkumar.nivara.domain.vault.content.VaultSourceOpenResult
import com.ashishkumar.nivara.domain.vault.content.VaultSourceSelectionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest

/** Serial, session-checkpointed import orchestration. A failed index commit deliberately leaves its final object orphaned. */
class DefaultVaultImportRepository(
    private val sources: VaultSourceDocumentProvider,
    private val objects: VaultContentStorage,
    private val crypto: VaultContentCrypto,
    private val index: VaultIndexRepository,
    private val random: SecureRandomSource,
) : VaultImportRepository {
    private val mutex = Mutex()

    override fun discard(sourceId: VaultSourceSelectionId) { sources.discard(sourceId) }

    override suspend fun importDocument(
        sourceId: VaultSourceSelectionId,
        vaultId: VaultId,
        authorizationCheckpoint: suspend () -> Boolean,
        onProgress: (Long, Long?) -> Unit,
    ): VaultImportResult = mutex.withLock {
        var contentDigestSha256: ByteArray? = null
        try {
            if (!isAuthorized(authorizationCheckpoint)) return@withLock VaultImportResult.AuthorizationExpired
            val metadata = when (val read = sources.metadata(sourceId)) {
                is VaultSourceMetadataRead.Available -> read.metadata
                VaultSourceMetadataRead.AccessDenied -> return@withLock VaultImportResult.SourceAccessDenied
                VaultSourceMetadataRead.Unavailable -> return@withLock VaultImportResult.SourceUnavailable
            }
            if (!validMetadata(metadata)) return@withLock VaultImportResult.SourceMetadataInvalid

            val current = when (val state = index.inspect(vaultId)) {
                is VaultIndexRead.Ready -> state
                VaultIndexRead.Missing, VaultIndexRead.ObjectsWithoutIndex,
                is VaultIndexRead.ContentWithoutIndex -> return@withLock VaultImportResult.IndexMissing
                VaultIndexRead.Corrupt -> return@withLock VaultImportResult.IndexUnreadable
                is VaultIndexRead.UnsupportedVersion -> return@withLock VaultImportResult.IndexUnsupported
                VaultIndexRead.AccessDenied, VaultIndexRead.Unavailable -> return@withLock VaultImportResult.IndexUnreadable
                is VaultIndexRead.VaultUnavailable -> return@withLock VaultImportResult.VaultUnavailable
            }
            if (current.items.size >= com.ashishkumar.nivara.domain.vault.content.VaultIndexCodec.MAX_ITEMS) {
                return@withLock VaultImportResult.IndexWriteFailed
            }
            val itemId = newItemId() ?: return@withLock VaultImportResult.Failed
            if (current.items.any { it.id == itemId }) return@withLock VaultImportResult.DuplicateId

            var encryptionInfo: com.ashishkumar.nivara.domain.vault.content.VaultContentEncryptionResult? = null
            var abortResult: VaultImportResult? = null
            val objectCommit = try {
                objects.writeObjectAtomically(
                    itemId = itemId,
                    authorizationCheckpoint = { isAuthorized(authorizationCheckpoint) },
                ) { output ->
                    val input = when (val opened = sources.open(sourceId)) {
                        is VaultSourceOpenResult.Opened -> opened.stream
                        VaultSourceOpenResult.AccessDenied -> {
                            abortResult = VaultImportResult.SourceAccessDenied
                            throw ImportAborted()
                        }
                        VaultSourceOpenResult.Unavailable -> {
                            abortResult = VaultImportResult.SourceUnavailable
                            throw ImportAborted()
                        }
                    }
                    input.use { source ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val digestingSource = DigestInputStream(source, digest)
                        when (val encrypted = crypto.encryptObject(
                            vaultId = vaultId,
                            itemId = itemId,
                            source = digestingSource,
                            destination = output,
                            expectedSourceSize = metadata.sizeBytes,
                            authorizationCheckpoint = { isAuthorized(authorizationCheckpoint) },
                            onProgress = { bytes -> onProgress(bytes, metadata.sizeBytes) },
                        )) {
                            is VaultContentCryptoResult.Success -> {
                                encryptionInfo = encrypted.value
                                contentDigestSha256 = digest.digest()
                                VaultObjectWriteInfo(encrypted.value.plaintextBytes, encrypted.value.objectBytes)
                            }
                            VaultContentCryptoResult.AuthorizationExpired -> {
                                abortResult = VaultImportResult.AuthorizationExpired
                                throw ImportAborted()
                            }
                            VaultContentCryptoResult.SourceSizeMismatch -> {
                                abortResult = VaultImportResult.SourceSizeMismatch
                                throw ImportAborted()
                            }
                            is VaultContentCryptoResult.VaultUnavailable -> {
                                abortResult = VaultImportResult.VaultUnavailable
                                throw ImportAborted()
                            }
                            VaultContentCryptoResult.AuthenticationFailed,
                            VaultContentCryptoResult.OperationFailed,
                            is VaultContentCryptoResult.UnsupportedVersion -> {
                                abortResult = VaultImportResult.EncryptionFailed
                                throw ImportAborted()
                            }
                        }
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                null
            }
            if (objectCommit !is VaultObjectCommitResult.Created) {
                encryptionInfo?.clear()
                contentDigestSha256?.fill(0)
                return@withLock abortResult ?: when (objectCommit) {
                    VaultObjectCommitResult.AccessDenied -> VaultImportResult.DestinationAccessDenied
                    VaultObjectCommitResult.SourceUnavailable -> VaultImportResult.SourceUnavailable
                    VaultObjectCommitResult.Conflict -> VaultImportResult.DuplicateId
                    VaultObjectCommitResult.DestinationUnavailable -> VaultImportResult.DestinationUnavailable
                    VaultObjectCommitResult.AuthorizationExpired -> VaultImportResult.AuthorizationExpired
                    VaultObjectCommitResult.VerificationFailed,
                    VaultObjectCommitResult.WriteFailed,
                    VaultObjectCommitResult.CleanupFailed -> VaultImportResult.Failed
                    null -> VaultImportResult.Failed
                    is VaultObjectCommitResult.Created -> VaultImportResult.Failed
                }
            }
            val completed = encryptionInfo ?: return@withLock VaultImportResult.Failed
            if (objectCommit.objectSizeBytes != completed.objectBytes) {
                completed.clear()
                contentDigestSha256?.fill(0)
                return@withLock VaultImportResult.Failed
            }

            val input = objects.openObject(itemId) ?: run {
                completed.clear()
                return@withLock VaultImportResult.DestinationUnavailable
            }
            val verification = input.use { encryptedObject ->
                crypto.verifyObject(vaultId, temporaryItem(itemId, metadata, completed), encryptedObject) {
                    isAuthorized(authorizationCheckpoint)
                }
            }
            when (verification) {
                is VaultContentCryptoResult.Success -> if (!verification.value) {
                    completed.clear()
                    return@withLock VaultImportResult.EncryptionFailed
                }
                VaultContentCryptoResult.AuthorizationExpired -> {
                    completed.clear()
                    return@withLock VaultImportResult.AuthorizationExpired
                }
                is VaultContentCryptoResult.VaultUnavailable -> {
                    completed.clear()
                    return@withLock VaultImportResult.VaultUnavailable
                }
                else -> {
                    completed.clear()
                    return@withLock VaultImportResult.EncryptionFailed
                }
            }
            if (!isAuthorized(authorizationCheckpoint)) {
                completed.clear()
                return@withLock VaultImportResult.AuthorizationExpired
            }

            val wrappedKey = completed.encryptedItemKey
            val item = try {
                VaultItem(
                    id = itemId,
                    originalFilename = metadata.filename,
                    originalMimeType = metadata.mimeType,
                    originalSizeBytes = completed.plaintextBytes,
                    importedAtEpochMillis = System.currentTimeMillis().coerceAtLeast(0),
                    objectFormatVersion = com.ashishkumar.nivara.domain.vault.content.VaultContentObjectCodec.VERSION,
                    objectSizeBytes = completed.objectBytes,
                    encryptedItemKey = wrappedKey,
                    contentDigestSha256 = contentDigestSha256,
                )
            } catch (_: Exception) {
                wrappedKey.fill(0)
                completed.clear()
                return@withLock VaultImportResult.SourceMetadataInvalid
            }
            wrappedKey.fill(0)
            contentDigestSha256?.fill(0)
            contentDigestSha256 = null
            completed.clear()

            when (val committed = index.addItem(vaultId, item, authorizationCheckpoint)) {
                is VaultIndexWriteResult.Added -> VaultImportResult.Imported(committed.item, current.items.size + 1)
                VaultIndexWriteResult.DuplicateId -> VaultImportResult.DuplicateId
                VaultIndexWriteResult.AccessDenied -> VaultImportResult.DestinationAccessDenied
                VaultIndexWriteResult.Unavailable -> VaultImportResult.DestinationUnavailable
                VaultIndexWriteResult.MissingIndex -> VaultImportResult.IndexMissing
                VaultIndexWriteResult.Corrupt -> VaultImportResult.IndexUnreadable
                is VaultIndexWriteResult.UnsupportedVersion -> VaultImportResult.IndexUnsupported
                is VaultIndexWriteResult.VaultUnavailable -> VaultImportResult.VaultUnavailable
                VaultIndexWriteResult.Failed -> VaultImportResult.IndexWriteFailed
                VaultIndexWriteResult.AuthorizationExpired -> VaultImportResult.AuthorizationExpired
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            VaultImportResult.Failed
        } finally {
            contentDigestSha256?.fill(0)
            sources.discard(sourceId)
        }
    }

    private suspend fun isAuthorized(checkpoint: suspend () -> Boolean): Boolean = try {
        checkpoint()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        false
    }

    private fun validMetadata(metadata: VaultSourceMetadata): Boolean =
        VaultItemValidation.validateFilename(metadata.filename) &&
            (metadata.mimeType == null || VaultItemValidation.validateMimeType(metadata.mimeType)) &&
            (metadata.sizeBytes == null || metadata.sizeBytes >= 0)

    private fun newItemId(): VaultItemId? {
        val bytes = try { random.generateBytes(VaultItemId.BYTE_COUNT) }
        catch (_: Exception) { return null }
        return try { VaultItemId.fromBytes(bytes) } finally { bytes.fill(0) }
    }

    private fun temporaryItem(
        itemId: VaultItemId,
        metadata: VaultSourceMetadata,
        encryption: com.ashishkumar.nivara.domain.vault.content.VaultContentEncryptionResult,
    ): VaultItem {
        val key = encryption.encryptedItemKey
        return try {
            VaultItem(
                id = itemId,
                originalFilename = metadata.filename,
                originalMimeType = metadata.mimeType,
                originalSizeBytes = encryption.plaintextBytes,
                importedAtEpochMillis = 0,
                objectFormatVersion = com.ashishkumar.nivara.domain.vault.content.VaultContentObjectCodec.VERSION,
                objectSizeBytes = encryption.objectBytes,
                encryptedItemKey = key,
            )
        } finally { key.fill(0) }
    }

    private class ImportAborted : IOException()
}
