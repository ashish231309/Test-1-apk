package com.ashishkumar.nivara.data.vault

import com.ashishkumar.nivara.data.security.AesGcmKeyWrappingService
import com.ashishkumar.nivara.data.security.JcaAesGcmEncryption
import com.ashishkumar.nivara.data.security.JcaSecureRandomSource
import com.ashishkumar.nivara.domain.security.Aes256Key
import com.ashishkumar.nivara.domain.security.DeviceKeyStore
import com.ashishkumar.nivara.domain.security.SecurityFailure
import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.vault.VaultDirectoryEntry
import com.ashishkumar.nivara.domain.vault.VaultFormatComponent
import com.ashishkumar.nivara.domain.vault.VaultInitializationFailure
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.VaultInitializationResult
import com.ashishkumar.nivara.domain.vault.VaultMetadataCodec
import com.ashishkumar.nivara.domain.vault.VaultMetadataDecode
import com.ashishkumar.nivara.domain.vault.VaultMetadataEnvelope
import com.ashishkumar.nivara.domain.vault.VaultMetadataFile
import com.ashishkumar.nivara.domain.vault.VaultRepository
import com.ashishkumar.nivara.domain.vault.VaultStatus
import com.ashishkumar.nivara.domain.vault.VaultStorage
import com.ashishkumar.nivara.domain.vault.VaultStorageCommitResult
import com.ashishkumar.nivara.domain.vault.VaultStorageSnapshot
import com.ashishkumar.nivara.domain.vault.VaultUnavailableReason
import com.ashishkumar.nivara.domain.vault.content.VaultContentCrypto
import com.ashishkumar.nivara.domain.vault.content.VaultContentCryptoResult
import com.ashishkumar.nivara.domain.vault.content.VaultContentEncryptionResult
import com.ashishkumar.nivara.domain.vault.content.VaultContentObjectCodec
import com.ashishkumar.nivara.domain.vault.content.VaultItem
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultVaultRepositoryTest {
    @Test
    fun newSelectedRootIsNotInitializedAndSuccessfulInitializationIsAuthenticatedAndIdempotent() = runTest {
        val harness = Harness()
        assertEquals(VaultStatus.NotInitialized, harness.repository.inspect())

        val initialized = harness.repository.initialize() as VaultInitializationResult.Initialized
        assertEquals(VaultStatus.Ready(initialized.vaultId), harness.repository.inspect())
        assertEquals(VaultInitializationResult.AlreadyInitialized, harness.repository.initialize())
        assertEquals(1, harness.storage.commitCalls)
        assertEquals("chosen-external-root", harness.storage.exactRootToken)
        assertTrue(harness.storage.metadataBytes.isNotEmpty())
        assertFalse(String(harness.storage.metadataBytes).contains("primary"))
    }

    @Test
    fun noRootAndUnavailableOrDeniedRootsNeverBecomeUninitialized() = runTest {
        val noRoot = Harness().also { it.storage.snapshot = VaultStorageSnapshot.RootNotSelected }
        assertEquals(VaultStatus.RootNotSelected, noRoot.repository.inspect())
        assertEquals(VaultInitializationResult.RootNotSelected, noRoot.repository.initialize())
        assertEquals(0, noRoot.storage.commitCalls)

        val unavailable = Harness().also { it.storage.snapshot = VaultStorageSnapshot.Unavailable }
        assertEquals(VaultStatus.Unavailable(VaultUnavailableReason.EXTERNAL_STORAGE), unavailable.repository.inspect())
        assertTrue(unavailable.repository.initialize() is VaultInitializationResult.Unavailable)
        assertEquals(0, unavailable.storage.commitCalls)

        val denied = Harness().also { it.storage.snapshot = VaultStorageSnapshot.AccessDenied }
        assertEquals(VaultStatus.AccessDenied, denied.repository.inspect())
        assertEquals(VaultInitializationResult.AccessDenied, denied.repository.initialize())
        assertEquals(0, denied.storage.commitCalls)
    }

    @Test
    fun malformedMetadataAndAuthenticationFailureAreCorruptNotEmpty() = runTest {
        val malformed = Harness().also {
            it.storage.snapshot = available(VaultMetadataFile.Present(byteArrayOf(9, 8, 7)), VaultDirectoryEntry.DIRECTORY)
        }
        assertEquals(VaultStatus.CorruptMetadata, malformed.repository.inspect())
        assertEquals(VaultInitializationResult.CorruptMetadata, malformed.repository.initialize())
        assertEquals(0, malformed.storage.commitCalls)

        val unreadable = Harness().also {
            it.storage.snapshot = available(VaultMetadataFile.Unreadable, VaultDirectoryEntry.DIRECTORY)
        }
        assertEquals(VaultStatus.CorruptMetadata, unreadable.repository.inspect())
        assertEquals(VaultInitializationResult.CorruptMetadata, unreadable.repository.initialize())
        assertEquals(0, unreadable.storage.commitCalls)

        val tampered = Harness()
        tampered.repository.initialize()
        val decoded = VaultMetadataCodec.decode(tampered.storage.metadataBytes) as VaultMetadataDecode.Supported
        val badHeader = decoded.metadata.encryptedHeader.also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        tampered.storage.replaceMetadata(
            VaultMetadataCodec.encode(
                VaultMetadataEnvelope(decoded.metadata.vaultId, decoded.metadata.wrappedContentKey, badHeader),
            ),
        )
        assertEquals(VaultStatus.CorruptMetadata, tampered.repository.inspect())
        assertNotEquals(VaultStatus.NotInitialized, tampered.repository.inspect())
        assertEquals(1, tampered.storage.commitCalls)
    }

    @Test
    fun unsupportedVersionAndInvalidStructureAreDistinctAndNeverOverwritten() = runTest {
        val harness = Harness()
        harness.repository.initialize()
        val future = harness.storage.metadataBytes.copyOf().also { it[11] = 2 }
        harness.storage.replaceMetadata(future)
        val status = harness.repository.inspect() as VaultStatus.UnsupportedVersion
        assertEquals(2, status.version)
        assertEquals(VaultInitializationResult.UnsupportedVersion(VaultFormatComponent.METADATA, 2), harness.repository.initialize())
        assertEquals(1, harness.storage.commitCalls)

        val missingData = Harness()
        missingData.repository.initialize()
        missingData.storage.snapshot = available(
            VaultMetadataFile.Present(missingData.storage.metadataBytes),
            VaultDirectoryEntry.MISSING,
        )
        assertEquals(VaultStatus.InvalidStructure, missingData.repository.inspect())
    }

    @Test
    fun wrongDeviceKeyAndUnexpectedDataEntriesFailClosed() = runTest {
        val wrongKey = Harness()
        wrongKey.repository.initialize()
        wrongKey.keyStore.replaceWithDifferentKey()
        assertEquals(VaultStatus.CorruptMetadata, wrongKey.repository.inspect())

        val nonemptyData = Harness()
        nonemptyData.repository.initialize()
        nonemptyData.storage.snapshot = available(
            VaultMetadataFile.Present(nonemptyData.storage.metadataBytes.copyOf()),
            VaultDirectoryEntry.DIRECTORY,
            unexpectedDataEntries = true,
        )
        assertEquals(VaultStatus.InvalidStructure, nonemptyData.repository.inspect())
        assertEquals(VaultInitializationResult.InvalidStructure, nonemptyData.repository.initialize())
        assertEquals(1, nonemptyData.storage.commitCalls)
    }

    @Test
    fun missingOrInvalidatedDeviceKeyIsUnavailableNotCorruptOrEmpty() = runTest {
        val harness = Harness()
        harness.repository.initialize()
        harness.keyStore.available = false
        assertEquals(
            VaultStatus.Unavailable(VaultUnavailableReason.DEVICE_KEY_MISSING),
            harness.repository.inspect(),
        )
        assertEquals(1, harness.storage.commitCalls)

        harness.keyStore.available = true
        harness.keyStore.invalidated = true
        assertEquals(
            VaultStatus.Unavailable(VaultUnavailableReason.DEVICE_KEY_INVALIDATED),
            harness.repository.inspect(),
        )
    }

    @Test
    fun directoryCreationMetadataWriteAndCleanupFailuresNeverReportSuccess() = runTest {
        val directory = Harness().also { it.storage.commitResult = VaultStorageCommitResult.DirectoryCreationFailed }
        assertEquals(
            VaultInitializationResult.Failed(VaultInitializationFailure.DIRECTORY_CREATION),
            directory.repository.initialize(),
        )
        assertEquals(0, directory.storage.metadataBytes.size)

        val write = Harness().also { it.storage.commitResult = VaultStorageCommitResult.MetadataWriteFailed }
        assertEquals(
            VaultInitializationResult.Failed(VaultInitializationFailure.METADATA_WRITE),
            write.repository.initialize(),
        )
        assertEquals(0, write.storage.metadataBytes.size)

        val atomic = Harness().also { it.storage.commitResult = VaultStorageCommitResult.AtomicCommitFailed }
        assertEquals(
            VaultInitializationResult.Failed(VaultInitializationFailure.ATOMIC_COMMIT),
            atomic.repository.initialize(),
        )

        val cleanup = Harness().also { it.storage.commitResult = VaultStorageCommitResult.CleanupFailed }
        assertEquals(
            VaultInitializationResult.Failed(VaultInitializationFailure.STORAGE_CLEANUP),
            cleanup.repository.initialize(),
        )
        assertEquals(0, cleanup.storage.metadataBytes.size)
    }

    @Test
    fun interruptedPartialStructureIsInvalidAndNeverTreatedAsAnEmptyVault() = runTest {
        val harness = Harness().also {
            it.storage.snapshot = available(VaultMetadataFile.Missing, VaultDirectoryEntry.DIRECTORY)
        }
        assertEquals(VaultStatus.InvalidStructure, harness.repository.inspect())
        assertEquals(VaultInitializationResult.InvalidStructure, harness.repository.initialize())
        assertEquals(0, harness.storage.commitCalls)
    }

    @Test
    fun simultaneousInitializationSerializesAndCreatesExactlyOneVault() = runTest {
        val harness = Harness()
        val first = async { harness.repository.initialize() }
        val second = async { harness.repository.initialize() }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it is VaultInitializationResult.Initialized })
        assertEquals(1, results.count { it == VaultInitializationResult.AlreadyInitialized })
        assertEquals(1, harness.storage.commitCalls)
        assertTrue(harness.repository.inspect() is VaultStatus.Ready)
    }

    @Test
    fun concurrentInspectionAndInitializationReturnOnlyExplicitStates() = runTest {
        val harness = Harness()
        val inspections = List(6) { async { harness.repository.inspect() } }
        val initialization = async { harness.repository.initialize() }
        val observed = inspections.awaitAll()
        assertTrue(observed.all { it == VaultStatus.NotInitialized || it is VaultStatus.Ready })
        assertTrue(initialization.await() is VaultInitializationResult.Initialized)
        assertTrue(harness.repository.inspect() is VaultStatus.Ready)
        assertEquals(1, harness.storage.commitCalls)
    }

    @Test
    fun contentCryptoUsesScopedVaultKeyForIndexAndStreamsPerItemKeys() = runTest {
        val harness = Harness()
        val vaultId = (harness.repository.initialize() as VaultInitializationResult.Initialized).vaultId
        val crypto = harness.repository as VaultContentCrypto
        val itemId = VaultItemId("11223344556677889900aabbccddeeff")
        val plaintext = ByteArray(220_321) { ((it * 13) and 0xff).toByte() }
        val encryptedObject = ByteArrayOutputStream()
        val encrypted = crypto.encryptObject(
            vaultId = vaultId,
            itemId = itemId,
            source = ByteArrayInputStream(plaintext),
            destination = encryptedObject,
            expectedSourceSize = plaintext.size.toLong(),
            authorizationCheckpoint = { true },
            onProgress = {},
        ) as VaultContentCryptoResult.Success<VaultContentEncryptionResult>
        val info = encrypted.value
        assertEquals(plaintext.size.toLong(), info.plaintextBytes)
        assertEquals(encryptedObject.size().toLong(), info.objectBytes)
        val wrappedItemKey = info.encryptedItemKey
        val item = VaultItem(
            id = itemId,
            originalFilename = "large document.bin",
            originalMimeType = "application/octet-stream",
            originalSizeBytes = plaintext.size.toLong(),
            importedAtEpochMillis = 1234,
            objectFormatVersion = VaultContentObjectCodec.VERSION,
            objectSizeBytes = info.objectBytes,
            encryptedItemKey = wrappedItemKey,
        )
        wrappedItemKey.fill(0)
        val encodedObject = encryptedObject.toByteArray()
        val verified = crypto.verifyObject(vaultId, item, ByteArrayInputStream(encodedObject)) { true }
        assertEquals(VaultContentCryptoResult.Success(true), verified)
        val quarantined = WipingByteArrayOutputStream()
        assertTrue(crypto.decryptObjectToQuarantine(
            vaultId, item, ByteArrayInputStream(encodedObject), quarantined,
        ) { true } is VaultContentCryptoResult.Success)
        val verifiedPlaintext = quarantined.toByteArray()
        assertTrue(plaintext.contentEquals(verifiedPlaintext))
        verifiedPlaintext.fill(0)
        val tamperedObject = encodedObject.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val rejectedQuarantine = WipingByteArrayOutputStream()
        assertEquals(
            VaultContentCryptoResult.AuthenticationFailed,
            crypto.decryptObjectToQuarantine(vaultId, item, ByteArrayInputStream(tamperedObject), rejectedQuarantine) { true },
        )
        // Failed streaming output remains in a private quarantine sink and is never returned as successful content.
        tamperedObject.fill(0)
        encodedObject.fill(0)

        val indexBytes = byteArrayOf(7, 8, 9, 10)
        val encryptedIndex = crypto.encryptIndex(vaultId, 3, indexBytes) as VaultContentCryptoResult.Success<ByteArray>
        val decryptedIndex = crypto.decryptIndex(vaultId, 3, encryptedIndex.value) as VaultContentCryptoResult.Success<ByteArray>
        assertTrue(indexBytes.contentEquals(decryptedIndex.value))
        assertEquals(VaultContentCryptoResult.AuthenticationFailed,
            crypto.decryptIndex(vaultId, 4, encryptedIndex.value))

        val organizationBytes = "private album title and item references".toByteArray()
        val encryptedOrganization = crypto.encryptOrganization(vaultId, 9, organizationBytes)
            as VaultContentCryptoResult.Success<ByteArray>
        assertFalse(organizationBytes.contentEquals(encryptedOrganization.value))
        assertFalse(containsBytes(encryptedOrganization.value, organizationBytes))
        val decryptedOrganization = crypto.decryptOrganization(vaultId, 9, encryptedOrganization.value)
            as VaultContentCryptoResult.Success<ByteArray>
        assertTrue(organizationBytes.contentEquals(decryptedOrganization.value))
        assertEquals(VaultContentCryptoResult.AuthenticationFailed,
            crypto.decryptOrganization(vaultId, 10, encryptedOrganization.value))
        assertFalse(crypto.decryptOrganization(
            VaultId("ffeeddccbbaa99887766554433221100"), 9, encryptedOrganization.value,
        ) is VaultContentCryptoResult.Success)
        assertEquals(VaultContentCryptoResult.AuthenticationFailed,
            crypto.decryptIndex(vaultId, 9, encryptedOrganization.value))
        organizationBytes.fill(0)
        encryptedOrganization.value.fill(0)
        decryptedOrganization.value.fill(0)
        info.clear()
        plaintext.fill(0)
        quarantined.wipe()
        rejectedQuarantine.wipe()
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean =
        needle.isNotEmpty() && (0..haystack.size - needle.size).any { start ->
            needle.indices.all { offset -> haystack[start + offset] == needle[offset] }
        }

    private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
        fun wipe() { buf.fill(0); reset() }
    }

    private fun available(
        metadata: VaultMetadataFile,
        directory: VaultDirectoryEntry,
        unexpectedDataEntries: Boolean = false,
    ) = VaultStorageSnapshot.Available(
        metadata,
        directory,
        unexpectedEntries = false,
        unexpectedDataEntries = unexpectedDataEntries,
    )

    private class Harness {
        val random: SecureRandomSource = JcaSecureRandomSource()
        val storage = FakeStorage()
        val keyStore = FakeDeviceKeyStore(random)
        val repository: VaultRepository = DefaultVaultRepository(
            storage = storage,
            encryption = JcaAesGcmEncryption(random),
            keyWrapping = AesGcmKeyWrappingService(JcaAesGcmEncryption(random)),
            deviceKeyStore = keyStore,
            random = random,
        )
    }

    private class FakeStorage : VaultStorage {
        var exactRootToken = "chosen-external-root"
        var snapshot: VaultStorageSnapshot = VaultStorageSnapshot.Available(
            VaultMetadataFile.Missing,
            VaultDirectoryEntry.MISSING,
            unexpectedEntries = false,
        )
        var metadataBytes = byteArrayOf()
        var commitCalls = 0
        var commitResult: VaultStorageCommitResult = VaultStorageCommitResult.Created

        override suspend fun inspect(): VaultStorageSnapshot = when (val current = snapshot) {
            is VaultStorageSnapshot.Available -> current.copy(
                metadata = when (val metadata = current.metadata) {
                    is VaultMetadataFile.Present -> VaultMetadataFile.Present(metadata.bytes.copyOf())
                    else -> metadata
                },
            )
            else -> current
        }

        override suspend fun initializeAtomically(metadataBytes: ByteArray): VaultStorageCommitResult {
            commitCalls++
            if (commitResult != VaultStorageCommitResult.Created) return commitResult
            if (snapshot !is VaultStorageSnapshot.Available) return VaultStorageCommitResult.Unavailable
            val current = snapshot as VaultStorageSnapshot.Available
            if (current.metadata !is VaultMetadataFile.Missing ||
                current.dataDirectory != VaultDirectoryEntry.MISSING || current.unexpectedEntries ||
                current.unexpectedDataEntries
            ) return VaultStorageCommitResult.RootNotEmpty
            this.metadataBytes = metadataBytes.copyOf()
            snapshot = VaultStorageSnapshot.Available(
                VaultMetadataFile.Present(this.metadataBytes.copyOf()),
                VaultDirectoryEntry.DIRECTORY,
                unexpectedEntries = false,
            )
            return VaultStorageCommitResult.Created
        }

        fun replaceMetadata(bytes: ByteArray) {
            metadataBytes.fill(0)
            metadataBytes = bytes.copyOf()
            snapshot = VaultStorageSnapshot.Available(
                VaultMetadataFile.Present(metadataBytes.copyOf()),
                VaultDirectoryEntry.DIRECTORY,
                unexpectedEntries = false,
            )
        }
    }

    private class FakeDeviceKeyStore(private val random: SecureRandomSource) : DeviceKeyStore {
        var available = true
        var invalidated = false
        private var key: Aes256Key? = null

        override fun createAes256Key(alias: String): Aes256Key {
            if (key != null) throw SecurityFailure.KeyAlreadyExists()
            val bytes = random.generateAes256KeyBytes()
            return try { Aes256Key.fromBytes(bytes).also { key = it } } finally { bytes.fill(0) }
        }

        override fun getAes256Key(alias: String): Aes256Key {
            if (invalidated) throw SecurityFailure.KeyInvalidated()
            if (!available) throw SecurityFailure.MissingKey()
            return key ?: throw SecurityFailure.MissingKey()
        }

        fun replaceWithDifferentKey() {
            val bytes = random.generateAes256KeyBytes()
            try { key = Aes256Key.fromBytes(bytes) } finally { bytes.fill(0) }
        }

        override fun deleteKey(alias: String) { key = null }
    }

}
