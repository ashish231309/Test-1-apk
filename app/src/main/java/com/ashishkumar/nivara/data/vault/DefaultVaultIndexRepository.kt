package com.ashishkumar.nivara.data.vault

import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.VaultStatus
import com.ashishkumar.nivara.domain.vault.content.VaultContentCrypto
import com.ashishkumar.nivara.domain.vault.content.VaultContentCryptoResult
import com.ashishkumar.nivara.domain.vault.content.VaultIndexCodec
import com.ashishkumar.nivara.domain.vault.content.VaultIndexEnvelopeCodec
import com.ashishkumar.nivara.domain.vault.content.VaultIndexFileCommitResult
import com.ashishkumar.nivara.domain.vault.content.VaultIndexFiles
import com.ashishkumar.nivara.domain.vault.content.VaultContentDiagnostics
import com.ashishkumar.nivara.domain.vault.content.VaultObjectDirectoryRead
import com.ashishkumar.nivara.domain.vault.content.VaultIndexInitializationResult
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRead
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRepository
import com.ashishkumar.nivara.domain.vault.content.VaultIndexWriteResult
import com.ashishkumar.nivara.domain.vault.content.VaultItem
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import com.ashishkumar.nivara.domain.vault.content.VaultContentStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Authenticated snapshots are immutable and generation-named; failed writes never replace the prior snapshot. */
class DefaultVaultIndexRepository(
    private val storage: VaultContentStorage,
    private val crypto: VaultContentCrypto,
) : VaultIndexRepository {
    private val mutex = Mutex()

    override suspend fun inspect(vaultId: VaultId): VaultIndexRead = mutex.withLock { readLocked(vaultId) }
    override suspend fun listItems(vaultId: VaultId): VaultIndexRead = inspect(vaultId)

    override suspend fun initializeEmpty(
        vaultId: VaultId,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultIndexInitializationResult = mutex.withLock {
        when (val dirs = storage.initializeContentDirectories()) {
            VaultIndexFileCommitResult.AccessDenied -> return@withLock VaultIndexInitializationResult.AccessDenied
            VaultIndexFileCommitResult.Unavailable -> return@withLock VaultIndexInitializationResult.Unavailable
            VaultIndexFileCommitResult.WriteFailed,
            VaultIndexFileCommitResult.VerificationFailed,
            VaultIndexFileCommitResult.Conflict -> return@withLock VaultIndexInitializationResult.WriteFailed
            VaultIndexFileCommitResult.AuthorizationExpired -> return@withLock VaultIndexInitializationResult.AuthorizationExpired
            VaultIndexFileCommitResult.Created -> Unit
        }
        when (val files = storage.inspectIndexFiles()) {
            is VaultIndexFiles.NoIndex -> {
                if (files.hasUnexpectedEntries) return@withLock VaultIndexInitializationResult.Corrupt
                if (files.hasObjects) return@withLock VaultIndexInitializationResult.ObjectsAlreadyPresent
                val plaintext = try { VaultIndexCodec.encode(vaultId, 0, emptyList()) }
                catch (_: Exception) { return@withLock VaultIndexInitializationResult.WriteFailed }
                try {
                    when (val encrypted = crypto.encryptIndex(vaultId, 0, plaintext)) {
                        is VaultContentCryptoResult.Success -> {
                            val bytes = encrypted.value
                            try {
                                when (storage.commitIndexFileAtomically(0, bytes, authorizationCheckpoint)) {
                                    VaultIndexFileCommitResult.Created -> {
                                        when (val check = readLocked(vaultId)) {
                                            is VaultIndexRead.Ready -> VaultIndexInitializationResult.Initialized
                                            else -> VaultIndexInitializationResult.WriteFailed
                                        }
                                    }
                                    VaultIndexFileCommitResult.AccessDenied -> VaultIndexInitializationResult.AccessDenied
                                    VaultIndexFileCommitResult.Unavailable -> VaultIndexInitializationResult.Unavailable
                                    VaultIndexFileCommitResult.AuthorizationExpired -> VaultIndexInitializationResult.AuthorizationExpired
                                    else -> VaultIndexInitializationResult.WriteFailed
                                }
                            } finally { bytes.fill(0) }
                        }
                        is VaultContentCryptoResult.VaultUnavailable -> VaultIndexInitializationResult.VaultUnavailable(encrypted.status)
                        is VaultContentCryptoResult.UnsupportedVersion -> VaultIndexInitializationResult.UnsupportedVersion
                        else -> VaultIndexInitializationResult.WriteFailed
                    }
                } finally { plaintext.fill(0) }
            }
            is VaultIndexFiles.Present -> when (val state = readLocked(vaultId)) {
                is VaultIndexRead.Ready -> VaultIndexInitializationResult.AlreadyInitialized
                is VaultIndexRead.UnsupportedVersion -> VaultIndexInitializationResult.UnsupportedVersion
                is VaultIndexRead.VaultUnavailable -> VaultIndexInitializationResult.VaultUnavailable(state.status)
                VaultIndexRead.AccessDenied -> VaultIndexInitializationResult.AccessDenied
                VaultIndexRead.Unavailable -> VaultIndexInitializationResult.Unavailable
                else -> VaultIndexInitializationResult.Corrupt
            }
            VaultIndexFiles.AccessDenied -> VaultIndexInitializationResult.AccessDenied
            VaultIndexFiles.Unavailable -> VaultIndexInitializationResult.Unavailable
        }
    }

    override suspend fun addItem(
        vaultId: VaultId,
        item: VaultItem,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultIndexWriteResult = mutex.withLock {
        when (val current = readLocked(vaultId)) {
            is VaultIndexRead.Ready -> {
                if (current.items.any { it.id == item.id }) return@withLock VaultIndexWriteResult.DuplicateId
                if (current.items.size >= VaultIndexCodec.MAX_ITEMS || current.generation == Long.MAX_VALUE) {
                    return@withLock VaultIndexWriteResult.Failed
                }
                val generation = current.generation + 1
                val updated = current.items + item
                val plaintext = try { VaultIndexCodec.encode(vaultId, generation, updated) }
                catch (_: Exception) { return@withLock VaultIndexWriteResult.Failed }
                try {
                    when (val encrypted = crypto.encryptIndex(vaultId, generation, plaintext)) {
                        is VaultContentCryptoResult.Success -> {
                            val bytes = encrypted.value
                            try {
                                when (storage.commitIndexFileAtomically(generation, bytes, authorizationCheckpoint)) {
                                    VaultIndexFileCommitResult.Created -> {
                                        when (val verified = readLocked(vaultId)) {
                                            is VaultIndexRead.Ready -> {
                                                val committed = verified.items.firstOrNull { it.id == item.id }
                                                if (committed == null) VaultIndexWriteResult.Failed
                                                else VaultIndexWriteResult.Added(committed, generation)
                                            }
                                            else -> VaultIndexWriteResult.Failed
                                        }
                                    }
                                    VaultIndexFileCommitResult.AccessDenied -> VaultIndexWriteResult.AccessDenied
                                    VaultIndexFileCommitResult.Unavailable -> VaultIndexWriteResult.Unavailable
                                    VaultIndexFileCommitResult.AuthorizationExpired -> VaultIndexWriteResult.AuthorizationExpired
                                    else -> VaultIndexWriteResult.Failed
                                }
                            } finally { bytes.fill(0) }
                        }
                        is VaultContentCryptoResult.VaultUnavailable -> VaultIndexWriteResult.VaultUnavailable(encrypted.status)
                        is VaultContentCryptoResult.UnsupportedVersion -> VaultIndexWriteResult.UnsupportedVersion(encrypted.version ?: -1)
                        else -> VaultIndexWriteResult.Failed
                    }
                } finally { plaintext.fill(0) }
            }
            VaultIndexRead.Missing, VaultIndexRead.ObjectsWithoutIndex,
            is VaultIndexRead.ContentWithoutIndex -> VaultIndexWriteResult.MissingIndex
            VaultIndexRead.Corrupt -> VaultIndexWriteResult.Corrupt
            is VaultIndexRead.UnsupportedVersion -> VaultIndexWriteResult.UnsupportedVersion(current.version)
            VaultIndexRead.Unavailable -> VaultIndexWriteResult.Unavailable
            VaultIndexRead.AccessDenied -> VaultIndexWriteResult.AccessDenied
            is VaultIndexRead.VaultUnavailable -> VaultIndexWriteResult.VaultUnavailable(current.status)
        }
    }

    private suspend fun readLocked(vaultId: VaultId): VaultIndexRead {
        val files = try { storage.inspectIndexFiles() }
        catch (failure: CancellationException) { throw failure }
        catch (_: SecurityException) { return VaultIndexRead.AccessDenied }
        catch (_: Exception) { return VaultIndexRead.Unavailable }
        val generation = when (files) {
            is VaultIndexFiles.NoIndex -> {
                if (!files.hasObjects) return VaultIndexRead.Missing
                return when (val listing = try { storage.inspectObjectDirectory() }
                    catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) { VaultObjectDirectoryRead.Unavailable }) {
                    is VaultObjectDirectoryRead.Available -> VaultIndexRead.ContentWithoutIndex(
                        listing.objectIds.size + listing.unrecognizedEntries,
                        listing.unfinishedObjects,
                    )
                    VaultObjectDirectoryRead.AccessDenied,
                    VaultObjectDirectoryRead.Unavailable -> VaultIndexRead.ObjectsWithoutIndex
                }
            }
            is VaultIndexFiles.Present -> {
                if (files.hasUnexpectedEntries || files.generations.isEmpty()) return VaultIndexRead.Corrupt
                files.generations.maxOrNull() ?: return VaultIndexRead.Corrupt
            }
            VaultIndexFiles.AccessDenied -> return VaultIndexRead.AccessDenied
            VaultIndexFiles.Unavailable -> return VaultIndexRead.Unavailable
        }
        val encoded = try { storage.readIndexFile(generation) }
        catch (failure: CancellationException) { throw failure }
        catch (_: SecurityException) { return VaultIndexRead.AccessDenied }
        catch (_: Exception) { return VaultIndexRead.Unavailable }
            ?: return VaultIndexRead.Corrupt
        if (encoded.size > VaultIndexEnvelopeCodec.MAX_ENVELOPE_BYTES) {
            encoded.fill(0)
            return VaultIndexRead.Corrupt
        }
        val outer = when (val parsed = VaultIndexEnvelopeCodec.decode(encoded)) {
            is VaultIndexEnvelopeCodec.DecodeResult.Valid -> parsed.value
            is VaultIndexEnvelopeCodec.DecodeResult.Unsupported -> {
                encoded.fill(0)
                return VaultIndexRead.UnsupportedVersion(parsed.version)
            }
            VaultIndexEnvelopeCodec.DecodeResult.Invalid -> {
                encoded.fill(0)
                return VaultIndexRead.Corrupt
            }
        }
        encoded.fill(0)
        if (outer.generation != generation) {
            outer.encryptedRecord.fill(0)
            return VaultIndexRead.Corrupt
        }
        val plaintext = try {
            when (val decrypted = crypto.decryptIndex(vaultId, generation, outer.encryptedRecord)) {
                is VaultContentCryptoResult.Success -> decrypted.value
                is VaultContentCryptoResult.VaultUnavailable -> return VaultIndexRead.VaultUnavailable(decrypted.status)
                is VaultContentCryptoResult.UnsupportedVersion -> return VaultIndexRead.UnsupportedVersion(decrypted.version ?: -1)
                VaultContentCryptoResult.AuthenticationFailed -> return VaultIndexRead.Corrupt
                VaultContentCryptoResult.AuthorizationExpired,
                VaultContentCryptoResult.SourceSizeMismatch,
                VaultContentCryptoResult.OperationFailed -> return VaultIndexRead.Unavailable
            }
        } finally { outer.encryptedRecord.fill(0) }
        try {
            return when (val decoded = VaultIndexCodec.decode(plaintext)) {
                is VaultIndexCodec.DecodeResult.Decoded -> {
                    if (decoded.vaultId != vaultId || decoded.generation != generation) VaultIndexRead.Corrupt
                    else {
                        val indexedIds = decoded.items.mapTo(HashSet<VaultItemId>()) { it.id }
                        val listing = try { storage.inspectObjectDirectory() }
                        catch (failure: CancellationException) { throw failure }
                        catch (_: Exception) { VaultObjectDirectoryRead.Unavailable }
                        val diagnostics = when (listing) {
                            is VaultObjectDirectoryRead.Available -> VaultContentDiagnostics(
                                missingContent = indexedIds.count { it !in listing.objectIds },
                                unindexedObjects = listing.objectIds.count { it !in indexedIds } + listing.unrecognizedEntries,
                                unfinishedObjects = listing.unfinishedObjects,
                            )
                            VaultObjectDirectoryRead.AccessDenied,
                            VaultObjectDirectoryRead.Unavailable -> null
                        }
                        VaultIndexRead.Ready(generation, decoded.items, diagnostics)
                    }
                }
                is VaultIndexCodec.DecodeResult.Unsupported -> VaultIndexRead.UnsupportedVersion(decoded.version)
                VaultIndexCodec.DecodeResult.Invalid -> VaultIndexRead.Corrupt
            }
        } finally { plaintext.fill(0) }
    }
}
