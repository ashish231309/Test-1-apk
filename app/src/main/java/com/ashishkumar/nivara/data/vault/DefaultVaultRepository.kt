package com.ashishkumar.nivara.data.vault

import com.ashishkumar.nivara.domain.security.Aes256Key
import com.ashishkumar.nivara.domain.security.AuthenticatedEncryption
import com.ashishkumar.nivara.domain.security.CryptoContext
import com.ashishkumar.nivara.domain.security.DeviceKeyStore
import com.ashishkumar.nivara.domain.security.KeyProtection
import com.ashishkumar.nivara.domain.security.KeyWrappingService
import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.security.SecurityFailure
import com.ashishkumar.nivara.domain.security.StreamAuthorizationExpired
import com.ashishkumar.nivara.domain.security.StreamingAuthenticatedEncryption
import com.ashishkumar.nivara.domain.vault.content.VaultContentCrypto
import com.ashishkumar.nivara.domain.vault.content.VaultContentCryptoResult
import com.ashishkumar.nivara.domain.vault.content.VaultContentEncryptionResult
import com.ashishkumar.nivara.domain.vault.content.VaultContentObjectCodec
import com.ashishkumar.nivara.domain.vault.content.VaultIndexCodec
import com.ashishkumar.nivara.domain.vault.content.VaultIndexEnvelopeCodec
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationEnvelopeCodec
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationLimits
import com.ashishkumar.nivara.domain.vault.content.VaultItem
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import com.ashishkumar.nivara.domain.security.WrappedKeyEnvelope
import com.ashishkumar.nivara.domain.security.EncryptedEnvelope
import com.ashishkumar.nivara.domain.vault.VaultFormatComponent
import com.ashishkumar.nivara.domain.vault.VaultHeaderCodec
import com.ashishkumar.nivara.domain.vault.VaultHeaderDecode
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.VaultInitializationFailure
import com.ashishkumar.nivara.domain.vault.VaultInitializationResult
import com.ashishkumar.nivara.domain.vault.VaultMetadataCodec
import com.ashishkumar.nivara.domain.vault.VaultMetadataDecode
import com.ashishkumar.nivara.domain.vault.VaultRepository
import com.ashishkumar.nivara.domain.vault.VaultStatus
import com.ashishkumar.nivara.domain.vault.VaultStorage
import com.ashishkumar.nivara.domain.vault.VaultStorageCommitResult
import com.ashishkumar.nivara.domain.vault.VaultStorageSnapshot
import com.ashishkumar.nivara.domain.vault.VaultUnavailableReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Crypto-aware vault boundary. It reuses the existing content key but has no credential or session access. */
class DefaultVaultRepository(
    private val storage: VaultStorage,
    private val encryption: AuthenticatedEncryption,
    private val keyWrapping: KeyWrappingService,
    private val deviceKeyStore: DeviceKeyStore,
    private val random: SecureRandomSource,
) : VaultRepository, VaultContentCrypto {
    private val mutex = Mutex()

    override suspend fun inspect(): VaultStatus = mutex.withLock { inspectLocked() }

    override suspend fun initialize(): VaultInitializationResult = mutex.withLock {
        when (val current = inspectLocked()) {
            VaultStatus.RootNotSelected -> return@withLock VaultInitializationResult.RootNotSelected
            VaultStatus.NotInitialized -> Unit
            is VaultStatus.Ready -> return@withLock VaultInitializationResult.AlreadyInitialized
            VaultStatus.AccessDenied -> return@withLock VaultInitializationResult.AccessDenied
            is VaultStatus.Unavailable -> return@withLock VaultInitializationResult.Unavailable(current.reason)
            VaultStatus.CorruptMetadata -> return@withLock VaultInitializationResult.CorruptMetadata
            is VaultStatus.UnsupportedVersion -> return@withLock VaultInitializationResult.UnsupportedVersion(
                current.component,
                current.version,
            )
            VaultStatus.InvalidStructure -> return@withLock VaultInitializationResult.InvalidStructure
            is VaultStatus.InitializationFailed -> return@withLock VaultInitializationResult.Failed(current.reason)
        }

        var idBytes: ByteArray? = null
        var contentKey: ByteArray? = null
        var headerPlaintext: ByteArray? = null
        var wrappedBytes: ByteArray? = null
        var encryptedHeaderBytes: ByteArray? = null
        var metadataBytes: ByteArray? = null
        try {
            val generatedId = random.generateBytes(VAULT_ID_BYTES)
            idBytes = generatedId
            val vaultId = VaultId.fromBytes(generatedId)
            val generatedContentKey = random.generateAes256KeyBytes()
            contentKey = generatedContentKey
            val wrappingKey = getOrCreateWrappingKey()
            val wrapped = keyWrapping.wrap(
                generatedContentKey,
                wrappingKey,
                KeyProtection.ANDROID_KEYSTORE,
                keyContext(vaultId),
            )
            wrappedBytes = wrapped.encode()
            val plaintextHeader = VaultHeaderCodec.encode(vaultId)
            headerPlaintext = plaintextHeader
            val contentKeyHandle = Aes256Key.fromBytes(generatedContentKey)
            val encryptedHeader = encryption.encrypt(plaintextHeader, contentKeyHandle, headerContext(vaultId))
            val encodedHeader = encryptedHeader.encode()
            encryptedHeaderBytes = encodedHeader
            val encodedWrappedKey = wrappedBytes ?: error("Wrapped content key was not encoded.")
            val encodedMetadata = VaultMetadataCodec.encode(
                com.ashishkumar.nivara.domain.vault.VaultMetadataEnvelope(
                    vaultId = vaultId,
                    wrappedContentKey = encodedWrappedKey,
                    encryptedHeader = encodedHeader,
                ),
            )
            metadataBytes = encodedMetadata

            when (val commit = storage.initializeAtomically(encodedMetadata)) {
                VaultStorageCommitResult.Created -> {
                    val verified = inspectLocked()
                    if (verified == VaultStatus.Ready(vaultId)) {
                        VaultInitializationResult.Initialized(vaultId)
                    } else {
                        VaultInitializationResult.Failed(VaultInitializationFailure.POST_WRITE_VERIFICATION)
                    }
                }
                VaultStorageCommitResult.RootNotEmpty -> VaultInitializationResult.InvalidStructure
                VaultStorageCommitResult.AccessDenied -> VaultInitializationResult.AccessDenied
                VaultStorageCommitResult.Unavailable -> VaultInitializationResult.Unavailable(
                    VaultUnavailableReason.EXTERNAL_STORAGE,
                )
                VaultStorageCommitResult.DirectoryCreationFailed -> VaultInitializationResult.Failed(
                    VaultInitializationFailure.DIRECTORY_CREATION,
                )
                VaultStorageCommitResult.MetadataWriteFailed -> VaultInitializationResult.Failed(
                    VaultInitializationFailure.METADATA_WRITE,
                )
                VaultStorageCommitResult.AtomicCommitFailed -> VaultInitializationResult.Failed(
                    VaultInitializationFailure.ATOMIC_COMMIT,
                )
                VaultStorageCommitResult.VerificationFailed -> VaultInitializationResult.Failed(
                    VaultInitializationFailure.POST_WRITE_VERIFICATION,
                )
                VaultStorageCommitResult.Failed -> VaultInitializationResult.Failed(
                    VaultInitializationFailure.STORAGE_COMMIT,
                )
                VaultStorageCommitResult.CleanupFailed -> VaultInitializationResult.Failed(
                    VaultInitializationFailure.STORAGE_CLEANUP,
                )
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: SecurityFailure) {
            VaultInitializationResult.Failed(VaultInitializationFailure.CRYPTOGRAPHIC_OPERATION)
        } catch (_: Exception) {
            VaultInitializationResult.Failed(VaultInitializationFailure.CRYPTOGRAPHIC_OPERATION)
        } finally {
            idBytes?.fill(0)
            contentKey?.fill(0)
            headerPlaintext?.fill(0)
            wrappedBytes?.fill(0)
            encryptedHeaderBytes?.fill(0)
            metadataBytes?.fill(0)
        }
    }

    private suspend fun inspectLocked(): VaultStatus {
        val snapshot = try {
            storage.inspect()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: SecurityException) {
            return VaultStatus.AccessDenied
        } catch (_: Exception) {
            return VaultStatus.Unavailable(VaultUnavailableReason.EXTERNAL_STORAGE)
        }
        return when (snapshot) {
            VaultStorageSnapshot.RootNotSelected -> VaultStatus.RootNotSelected
            VaultStorageSnapshot.AccessDenied -> VaultStatus.AccessDenied
            VaultStorageSnapshot.Unavailable -> VaultStatus.Unavailable(VaultUnavailableReason.EXTERNAL_STORAGE)
            is VaultStorageSnapshot.Available -> inspectAvailable(snapshot)
        }
    }

    private fun inspectAvailable(snapshot: VaultStorageSnapshot.Available): VaultStatus {
        if (snapshot.unexpectedEntries || snapshot.unexpectedDataEntries) return VaultStatus.InvalidStructure
        return when (val metadataFile = snapshot.metadata) {
            com.ashishkumar.nivara.domain.vault.VaultMetadataFile.Missing ->
                if (snapshot.dataDirectory == com.ashishkumar.nivara.domain.vault.VaultDirectoryEntry.MISSING) {
                    VaultStatus.NotInitialized
                } else {
                    VaultStatus.InvalidStructure
                }
            com.ashishkumar.nivara.domain.vault.VaultMetadataFile.Unreadable -> VaultStatus.CorruptMetadata
            com.ashishkumar.nivara.domain.vault.VaultMetadataFile.AccessDenied -> VaultStatus.AccessDenied
            com.ashishkumar.nivara.domain.vault.VaultMetadataFile.Unavailable ->
                VaultStatus.Unavailable(VaultUnavailableReason.EXTERNAL_STORAGE)
            com.ashishkumar.nivara.domain.vault.VaultMetadataFile.WrongType -> VaultStatus.InvalidStructure
            is com.ashishkumar.nivara.domain.vault.VaultMetadataFile.Present -> {
                if (snapshot.dataDirectory != com.ashishkumar.nivara.domain.vault.VaultDirectoryEntry.DIRECTORY) {
                    metadataFile.bytes.fill(0)
                    return VaultStatus.InvalidStructure
                }
                try {
                    inspectMetadata(metadataFile.bytes)
                } finally {
                    metadataFile.bytes.fill(0)
                }
            }
        }
    }

    private fun inspectMetadata(bytes: ByteArray): VaultStatus {
        val decoded = when (val result = VaultMetadataCodec.decode(bytes)) {
            VaultMetadataDecode.Invalid -> return VaultStatus.CorruptMetadata
            is VaultMetadataDecode.UnsupportedVersion -> return VaultStatus.UnsupportedVersion(
                VaultFormatComponent.METADATA,
                result.version,
            )
            is VaultMetadataDecode.Supported -> result.metadata
        }
        val wrappedBytes = decoded.wrappedContentKey
        val headerEnvelopeBytes = decoded.encryptedHeader
        var contentKey: ByteArray? = null
        var headerPlaintext: ByteArray? = null
        try {
            val wrapped = try {
                WrappedKeyEnvelope.decode(wrappedBytes)
            } catch (_: SecurityFailure.UnsupportedEnvelopeVersion) {
                return VaultStatus.UnsupportedVersion(VaultFormatComponent.KEY_ENVELOPE, null)
            } catch (_: SecurityFailure.UnsupportedAlgorithm) {
                return VaultStatus.UnsupportedVersion(VaultFormatComponent.KEY_ENVELOPE, null)
            } catch (_: SecurityFailure) {
                return VaultStatus.CorruptMetadata
            }
            if (wrapped.protection != KeyProtection.ANDROID_KEYSTORE) return VaultStatus.CorruptMetadata

            val deviceKey = try {
                deviceKeyStore.getAes256Key(KEY_ALIAS)
            } catch (_: SecurityFailure.MissingKey) {
                return VaultStatus.Unavailable(VaultUnavailableReason.DEVICE_KEY_MISSING)
            } catch (_: SecurityFailure.KeyInvalidated) {
                return VaultStatus.Unavailable(VaultUnavailableReason.DEVICE_KEY_INVALIDATED)
            } catch (_: SecurityFailure) {
                return VaultStatus.Unavailable(VaultUnavailableReason.CRYPTOGRAPHIC_SERVICE)
            } catch (_: Exception) {
                return VaultStatus.Unavailable(VaultUnavailableReason.CRYPTOGRAPHIC_SERVICE)
            }
            val unwrappedKey = try {
                keyWrapping.unwrap(
                    wrapped,
                    deviceKey,
                    KeyProtection.ANDROID_KEYSTORE,
                    keyContext(decoded.vaultId),
                )
            } catch (_: SecurityFailure.UnsupportedEnvelopeVersion) {
                return VaultStatus.UnsupportedVersion(VaultFormatComponent.KEY_ENVELOPE, null)
            } catch (_: SecurityFailure.UnsupportedAlgorithm) {
                return VaultStatus.UnsupportedVersion(VaultFormatComponent.KEY_ENVELOPE, null)
            } catch (_: SecurityFailure.AuthenticationFailed) {
                return VaultStatus.CorruptMetadata
            } catch (_: SecurityFailure) {
                return VaultStatus.CorruptMetadata
            } catch (_: Exception) {
                return VaultStatus.Unavailable(VaultUnavailableReason.CRYPTOGRAPHIC_SERVICE)
            }
            contentKey = unwrappedKey
            val contentKeyHandle = try {
                Aes256Key.fromBytes(unwrappedKey)
            } catch (_: SecurityFailure) {
                return VaultStatus.CorruptMetadata
            }
            val encryptedHeader = try {
                EncryptedEnvelope.decode(headerEnvelopeBytes)
            } catch (_: SecurityFailure.UnsupportedEnvelopeVersion) {
                return VaultStatus.UnsupportedVersion(VaultFormatComponent.ENCRYPTED_HEADER, null)
            } catch (_: SecurityFailure.UnsupportedAlgorithm) {
                return VaultStatus.UnsupportedVersion(VaultFormatComponent.ENCRYPTED_HEADER, null)
            } catch (_: SecurityFailure) {
                return VaultStatus.CorruptMetadata
            }
            val decryptedHeader = try {
                encryption.decrypt(encryptedHeader, contentKeyHandle, headerContext(decoded.vaultId))
            } catch (_: SecurityFailure.UnsupportedEnvelopeVersion) {
                return VaultStatus.UnsupportedVersion(VaultFormatComponent.ENCRYPTED_HEADER, null)
            } catch (_: SecurityFailure.UnsupportedAlgorithm) {
                return VaultStatus.UnsupportedVersion(VaultFormatComponent.ENCRYPTED_HEADER, null)
            } catch (_: SecurityFailure.AuthenticationFailed) {
                return VaultStatus.CorruptMetadata
            } catch (_: SecurityFailure) {
                return VaultStatus.CorruptMetadata
            } catch (_: Exception) {
                return VaultStatus.Unavailable(VaultUnavailableReason.CRYPTOGRAPHIC_SERVICE)
            }
            headerPlaintext = decryptedHeader
            return when (val header = VaultHeaderCodec.decode(decryptedHeader)) {
                VaultHeaderDecode.Invalid -> VaultStatus.CorruptMetadata
                is VaultHeaderDecode.UnsupportedVersion -> VaultStatus.UnsupportedVersion(
                    VaultFormatComponent.ENCRYPTED_HEADER,
                    header.version,
                )
                is VaultHeaderDecode.Supported -> if (header.vaultId == decoded.vaultId) {
                    VaultStatus.Ready(decoded.vaultId)
                } else {
                    VaultStatus.CorruptMetadata
                }
            }
        } finally {
            wrappedBytes.fill(0)
            headerEnvelopeBytes.fill(0)
            contentKey?.fill(0)
            headerPlaintext?.fill(0)
        }
    }

    override suspend fun encryptIndex(
        vaultId: VaultId,
        generation: Long,
        plaintext: ByteArray,
    ): VaultContentCryptoResult<ByteArray> {
        if (generation < 0 || plaintext.size > VaultIndexCodec.MAX_INDEX_BYTES) {
            return VaultContentCryptoResult.OperationFailed
        }
        return withContentKey(vaultId) { key ->
            val encrypted = encryption.encrypt(plaintext, key, indexContext(vaultId, generation)).encode()
            VaultContentCryptoResult.Success(VaultIndexEnvelopeCodec.encode(generation, encrypted).also { encrypted.fill(0) })
        }
    }

    override suspend fun encryptOrganization(
        vaultId: VaultId,
        generation: Long,
        plaintext: ByteArray,
    ): VaultContentCryptoResult<ByteArray> {
        if (generation < 1 || plaintext.size > VaultOrganizationLimits.MAX_PLAINTEXT_BYTES) {
            return VaultContentCryptoResult.OperationFailed
        }
        return withContentKey(vaultId) { key ->
            val encryptedEnvelope = encryption.encrypt(plaintext, key, organizationContext(vaultId, generation)).encode()
            try {
                VaultContentCryptoResult.Success(VaultOrganizationEnvelopeCodec.encode(generation, encryptedEnvelope))
            } finally { encryptedEnvelope.fill(0) }
        }
    }

    override suspend fun decryptOrganization(
        vaultId: VaultId,
        generation: Long,
        encoded: ByteArray,
    ): VaultContentCryptoResult<ByteArray> {
        if (generation < 1 || encoded.size > VaultOrganizationLimits.MAX_STORED_RECORD_BYTES) {
            return VaultContentCryptoResult.OperationFailed
        }
        val outer = when (val decoded = VaultOrganizationEnvelopeCodec.decode(encoded)) {
            is VaultOrganizationEnvelopeCodec.DecodeResult.Valid -> decoded.record
            is VaultOrganizationEnvelopeCodec.DecodeResult.Unsupported ->
                return VaultContentCryptoResult.UnsupportedVersion(decoded.version)
            VaultOrganizationEnvelopeCodec.DecodeResult.Invalid -> return VaultContentCryptoResult.AuthenticationFailed
        }
        if (outer.generation != generation) {
            outer.encryptedEnvelope.fill(0)
            return VaultContentCryptoResult.AuthenticationFailed
        }
        return try {
            withContentKey(vaultId) { key ->
                val envelope = try { EncryptedEnvelope.decode(outer.encryptedEnvelope) }
                catch (_: SecurityFailure.UnsupportedEnvelopeVersion) {
                    return@withContentKey VaultContentCryptoResult.UnsupportedVersion(null)
                } catch (_: SecurityFailure.UnsupportedAlgorithm) {
                    return@withContentKey VaultContentCryptoResult.UnsupportedVersion(null)
                } catch (_: SecurityFailure) {
                    return@withContentKey VaultContentCryptoResult.AuthenticationFailed
                }
                VaultContentCryptoResult.Success(
                    encryption.decrypt(envelope, key, organizationContext(vaultId, generation)),
                )
            }
        } finally { outer.encryptedEnvelope.fill(0) }
    }

    override suspend fun decryptIndex(
        vaultId: VaultId,
        generation: Long,
        encoded: ByteArray,
    ): VaultContentCryptoResult<ByteArray> {
        if (generation < 0 || encoded.size > VaultIndexEnvelopeCodec.MAX_ENVELOPE_BYTES) {
            return VaultContentCryptoResult.OperationFailed
        }
        val outer = when (val decoded = VaultIndexEnvelopeCodec.decode(encoded)) {
            is VaultIndexEnvelopeCodec.DecodeResult.Valid -> decoded.value
            is VaultIndexEnvelopeCodec.DecodeResult.Unsupported -> return VaultContentCryptoResult.OperationFailed
            VaultIndexEnvelopeCodec.DecodeResult.Invalid -> return VaultContentCryptoResult.AuthenticationFailed
        }
        if (outer.generation != generation) {
            outer.encryptedRecord.fill(0)
            return VaultContentCryptoResult.AuthenticationFailed
        }
        return try {
            withContentKey(vaultId) { key ->
                val envelope = try { EncryptedEnvelope.decode(outer.encryptedRecord) }
                catch (_: SecurityFailure.UnsupportedEnvelopeVersion) {
                    return@withContentKey VaultContentCryptoResult.UnsupportedVersion(null)
                } catch (_: SecurityFailure.UnsupportedAlgorithm) {
                    return@withContentKey VaultContentCryptoResult.UnsupportedVersion(null)
                } catch (_: SecurityFailure) {
                    return@withContentKey VaultContentCryptoResult.AuthenticationFailed
                }
                VaultContentCryptoResult.Success(encryption.decrypt(envelope, key, indexContext(vaultId, generation)))
            }
        } finally {
            outer.encryptedRecord.fill(0)
        }
    }

    override suspend fun encryptObject(
        vaultId: VaultId,
        itemId: VaultItemId,
        source: InputStream,
        destination: OutputStream,
        expectedSourceSize: Long?,
        authorizationCheckpoint: suspend () -> Boolean,
        onProgress: (Long) -> Unit,
    ): VaultContentCryptoResult<VaultContentEncryptionResult> {
        val streaming = encryption as? StreamingAuthenticatedEncryption
            ?: return VaultContentCryptoResult.OperationFailed
        return withContentKey(vaultId) { vaultKey ->
            var itemKeyBytes: ByteArray? = null
            var nonce: ByteArray? = null
            var header: ByteArray? = null
            var wrappedItemKey: ByteArray? = null
            try {
                itemKeyBytes = random.generateAes256KeyBytes()
                val itemKey = Aes256Key.fromBytes(itemKeyBytes!!)
                val wrapped = encryption.encrypt(itemKeyBytes!!, vaultKey, itemKeyContext(vaultId, itemId)).encode()
                wrappedItemKey = wrapped
                nonce = random.generateGcmNonce()
                if (nonce!!.size != VaultContentObjectCodec.NONCE_BYTES) {
                    return@withContentKey VaultContentCryptoResult.OperationFailed
                }
                header = VaultContentObjectCodec.encodeHeader(itemId, nonce!!)
                destination.write(header!!)
                val summary = streaming.encryptStream(
                    input = source,
                    output = destination,
                    key = itemKey,
                    nonce = nonce!!,
                    context = objectContext(vaultId, header!!),
                    authorizationCheckpoint = authorizationCheckpoint,
                    onProgress = onProgress,
                )
                if (expectedSourceSize != null && summary.plaintextBytes != expectedSourceSize) {
                    return@withContentKey VaultContentCryptoResult.SourceSizeMismatch
                }
                val result = VaultContentEncryptionResult(
                    plaintextBytes = summary.plaintextBytes,
                    objectBytes = Math.addExact(VaultContentObjectCodec.HEADER_BYTES.toLong(), summary.ciphertextBytes),
                    encryptedItemKey = wrapped,
                )
                VaultContentCryptoResult.Success(result)
            } finally {
                itemKeyBytes?.fill(0)
                nonce?.fill(0)
                header?.fill(0)
                wrappedItemKey?.fill(0)
            }
        }
    }

    override suspend fun verifyObject(
        vaultId: VaultId,
        item: VaultItem,
        source: InputStream,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultContentCryptoResult<Boolean> = when (
        val result = decryptObjectToQuarantine(vaultId, item, source, DiscardOutputStream, authorizationCheckpoint)
    ) {
        is VaultContentCryptoResult.Success -> VaultContentCryptoResult.Success(true)
        is VaultContentCryptoResult.VaultUnavailable -> result
        VaultContentCryptoResult.AuthenticationFailed -> result
        is VaultContentCryptoResult.UnsupportedVersion -> result
        VaultContentCryptoResult.OperationFailed -> result
        VaultContentCryptoResult.AuthorizationExpired -> result
        VaultContentCryptoResult.SourceSizeMismatch -> result
    }

    override suspend fun decryptObjectToQuarantine(
        vaultId: VaultId,
        item: VaultItem,
        source: InputStream,
        destination: OutputStream,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultContentCryptoResult<com.ashishkumar.nivara.domain.vault.content.VaultContentReadSummary> {
        val streaming = encryption as? StreamingAuthenticatedEncryption
            ?: return VaultContentCryptoResult.OperationFailed
        return withContentKey(vaultId) { vaultKey ->
            var encodedKey: ByteArray? = null
            var itemKeyBytes: ByteArray? = null
            var headerBytes: ByteArray? = null
            var nonce: ByteArray? = null
            var decodedHeaderCopy: ByteArray? = null
            try {
                val header = ByteArray(VaultContentObjectCodec.HEADER_BYTES)
                headerBytes = header
                readFully(source, header)
                val decoded = when (val result = VaultContentObjectCodec.decodeHeader(header)) {
                    is VaultContentObjectCodec.HeaderResult.Valid -> result.header
                    is VaultContentObjectCodec.HeaderResult.Unsupported ->
                        return@withContentKey VaultContentCryptoResult.UnsupportedVersion(result.version)
                    VaultContentObjectCodec.HeaderResult.Invalid -> return@withContentKey VaultContentCryptoResult.AuthenticationFailed
                }
                decodedHeaderCopy = decoded.encoded
                if (decoded.itemId != item.id || item.objectFormatVersion != decoded.version) {
                    return@withContentKey VaultContentCryptoResult.AuthenticationFailed
                }
                nonce = decoded.nonce
                encodedKey = item.encryptedItemKey
                val wrapped = try { EncryptedEnvelope.decode(encodedKey!!) }
                catch (_: SecurityFailure) { return@withContentKey VaultContentCryptoResult.AuthenticationFailed }
                itemKeyBytes = try { encryption.decrypt(wrapped, vaultKey, itemKeyContext(vaultId, item.id)) }
                catch (_: SecurityFailure.AuthenticationFailed) { return@withContentKey VaultContentCryptoResult.AuthenticationFailed }
                if (itemKeyBytes!!.size != Aes256Key.KEY_BYTES) {
                    return@withContentKey VaultContentCryptoResult.AuthenticationFailed
                }
                val itemKey = Aes256Key.fromBytes(itemKeyBytes!!)
                val summary = streaming.decryptStream(
                    input = source,
                    output = destination,
                    key = itemKey,
                    nonce = nonce!!,
                    context = objectContext(vaultId, header),
                    authorizationCheckpoint = authorizationCheckpoint,
                )
                val objectBytes = Math.addExact(VaultContentObjectCodec.HEADER_BYTES.toLong(), summary.ciphertextBytes)
                if (summary.plaintextBytes != item.originalSizeBytes || objectBytes != item.objectSizeBytes) {
                    return@withContentKey VaultContentCryptoResult.AuthenticationFailed
                }
                VaultContentCryptoResult.Success(
                    com.ashishkumar.nivara.domain.vault.content.VaultContentReadSummary(summary.plaintextBytes, objectBytes),
                )
            } finally {
                encodedKey?.fill(0)
                itemKeyBytes?.fill(0)
                headerBytes?.fill(0)
                nonce?.fill(0)
                decodedHeaderCopy?.fill(0)
            }
        }
    }

    private suspend fun <T> withContentKey(
        expectedVaultId: VaultId,
        operation: suspend (Aes256Key) -> VaultContentCryptoResult<T>,
    ): VaultContentCryptoResult<T> = mutex.withLock contentLock@{
        val snapshot = try {
            storage.inspect()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: SecurityException) {
            return@contentLock VaultContentCryptoResult.VaultUnavailable(VaultStatus.AccessDenied)
        } catch (_: Exception) {
            return@contentLock VaultContentCryptoResult.VaultUnavailable(
                VaultStatus.Unavailable(VaultUnavailableReason.EXTERNAL_STORAGE),
            )
        }
        val available = snapshot as? VaultStorageSnapshot.Available
            ?: return@contentLock VaultContentCryptoResult.VaultUnavailable(snapshotStatus(snapshot))
        if (available.unexpectedEntries || available.unexpectedDataEntries) {
            return@contentLock VaultContentCryptoResult.VaultUnavailable(VaultStatus.InvalidStructure)
        }
        val metadataFile = available.metadata as? com.ashishkumar.nivara.domain.vault.VaultMetadataFile.Present
            ?: return@contentLock VaultContentCryptoResult.VaultUnavailable(inspectAvailable(available))
        var rawKey: ByteArray? = null
        try {
            val status = inspectMetadata(metadataFile.bytes)
            if (status != VaultStatus.Ready(expectedVaultId)) {
                return@contentLock VaultContentCryptoResult.VaultUnavailable(status)
            }
            val metadata = when (val decoded = VaultMetadataCodec.decode(metadataFile.bytes)) {
                is VaultMetadataDecode.Supported -> decoded.metadata
                else -> return@contentLock VaultContentCryptoResult.VaultUnavailable(VaultStatus.CorruptMetadata)
            }
            val wrapped = WrappedKeyEnvelope.decode(metadata.wrappedContentKey)
            val deviceKey = deviceKeyStore.getAes256Key(KEY_ALIAS)
            val unwrapped = keyWrapping.unwrap(
                wrapped,
                deviceKey,
                KeyProtection.ANDROID_KEYSTORE,
                keyContext(expectedVaultId),
            )
            rawKey = unwrapped
            val key = Aes256Key.fromBytes(unwrapped)
            try {
                operation(key)
            } catch (failure: StreamAuthorizationExpired) {
                VaultContentCryptoResult.AuthorizationExpired
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: SecurityFailure.AuthenticationFailed) {
                VaultContentCryptoResult.AuthenticationFailed
            } catch (_: Exception) {
                VaultContentCryptoResult.OperationFailed
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: SecurityFailure.AuthenticationFailed) {
            VaultContentCryptoResult.AuthenticationFailed
        } catch (_: SecurityFailure.MissingKey) {
            VaultContentCryptoResult.VaultUnavailable(
                VaultStatus.Unavailable(VaultUnavailableReason.DEVICE_KEY_MISSING),
            )
        } catch (_: SecurityFailure.KeyInvalidated) {
            VaultContentCryptoResult.VaultUnavailable(
                VaultStatus.Unavailable(VaultUnavailableReason.DEVICE_KEY_INVALIDATED),
            )
        } catch (_: Exception) {
            VaultContentCryptoResult.OperationFailed
        } finally {
            rawKey?.fill(0)
            metadataFile.bytes.fill(0)
        }
    }

    private fun snapshotStatus(snapshot: VaultStorageSnapshot): VaultStatus = when (snapshot) {
        VaultStorageSnapshot.RootNotSelected -> VaultStatus.RootNotSelected
        VaultStorageSnapshot.AccessDenied -> VaultStatus.AccessDenied
        VaultStorageSnapshot.Unavailable -> VaultStatus.Unavailable(VaultUnavailableReason.EXTERNAL_STORAGE)
        is VaultStorageSnapshot.Available -> inspectAvailable(snapshot)
    }

    private fun itemKeyContext(vaultId: VaultId, itemId: VaultItemId): CryptoContext {
        val itemBytes = itemId.toBytes()
        val binding = combinedBinding(vaultId, itemBytes)
        return try { CryptoContext(ITEM_KEY_PURPOSE, binding) }
        finally { itemBytes.fill(0); binding.fill(0) }
    }

    private fun objectContext(vaultId: VaultId, header: ByteArray): CryptoContext {
        val binding = combinedBinding(vaultId, header)
        return try { CryptoContext(OBJECT_PURPOSE, binding) } finally { binding.fill(0) }
    }

    private fun indexContext(vaultId: VaultId, generation: Long): CryptoContext {
        val binding = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).put(vaultId.toBytes()).putLong(generation).array()
        return try { CryptoContext(INDEX_PURPOSE, binding) } finally { binding.fill(0) }
    }

    private fun organizationContext(vaultId: VaultId, generation: Long): CryptoContext {
        val vaultBytes = vaultId.toBytes()
        val binding = ByteBuffer.allocate(vaultBytes.size + 8)
            .order(ByteOrder.BIG_ENDIAN).put(vaultBytes).putLong(generation).array()
        return try { CryptoContext(ORGANIZATION_PURPOSE, binding) }
        finally { vaultBytes.fill(0); binding.fill(0) }
    }

    private fun combinedBinding(vaultId: VaultId, bytes: ByteArray): ByteArray {
        val id = vaultId.toBytes()
        return try { ByteArray(id.size + bytes.size).also { it.writeAt(0, id); it.writeAt(id.size, bytes) } }
        finally { id.fill(0) }
    }

    private fun ByteArray.writeAt(offset: Int, source: ByteArray) = System.arraycopy(source, 0, this, offset, source.size)

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw SecurityFailure.InvalidEnvelope()
            if (count > 0) offset += count
        }
    }

    private object DiscardOutputStream : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }

    private fun getOrCreateWrappingKey(): Aes256Key = try {
        deviceKeyStore.createAes256Key(KEY_ALIAS)
    } catch (_: SecurityFailure.KeyAlreadyExists) {
        deviceKeyStore.getAes256Key(KEY_ALIAS)
    }

    private fun keyContext(vaultId: VaultId): CryptoContext = context(KEY_PURPOSE, vaultId)

    private fun headerContext(vaultId: VaultId): CryptoContext = context(HEADER_PURPOSE, vaultId)

    private fun context(purpose: String, vaultId: VaultId): CryptoContext {
        val binding = vaultId.toBytes()
        return try {
            CryptoContext(purpose, binding)
        } finally {
            binding.fill(0)
        }
    }

    private companion object {
        const val KEY_ALIAS = "vault_content_wrap_v1"
        const val KEY_PURPOSE = "nivara.vault.content-key.wrap.v1"
        const val HEADER_PURPOSE = "nivara.vault.header.v1"
        const val INDEX_PURPOSE = "nivara.vault.content-index.v1"
        const val ORGANIZATION_PURPOSE = "nivara.vault.organization-metadata.v1"
        const val ITEM_KEY_PURPOSE = "nivara.vault.item-key.wrap.v1"
        const val OBJECT_PURPOSE = "nivara.vault.content-object.v1"
        const val VAULT_ID_BYTES = 16
    }
}
