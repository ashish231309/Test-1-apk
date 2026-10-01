package com.ashishkumar.nivara.data.vault

import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.content.VaultContentCrypto
import com.ashishkumar.nivara.domain.vault.content.VaultContentCryptoResult
import com.ashishkumar.nivara.domain.vault.content.VaultContentEncryptionResult
import com.ashishkumar.nivara.domain.vault.content.VaultContentObjectCodec
import com.ashishkumar.nivara.domain.vault.content.VaultContentStorage
import com.ashishkumar.nivara.domain.vault.content.VaultIndexCodec
import com.ashishkumar.nivara.domain.vault.content.VaultIndexEnvelopeCodec
import com.ashishkumar.nivara.domain.vault.content.VaultIndexFileCommitResult
import com.ashishkumar.nivara.domain.vault.content.VaultIndexFiles
import com.ashishkumar.nivara.domain.vault.content.VaultIndexInitializationResult
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRead
import com.ashishkumar.nivara.domain.vault.content.VaultIndexWriteResult
import com.ashishkumar.nivara.domain.vault.content.VaultItem
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import com.ashishkumar.nivara.domain.vault.content.VaultObjectCommitResult
import com.ashishkumar.nivara.domain.vault.content.VaultObjectDirectoryRead
import com.ashishkumar.nivara.domain.vault.content.VaultObjectWriteInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

class DefaultVaultIndexRepositoryTest {
    private val vaultId = VaultId("00112233445566778899aabbccddeeff")

    @Test fun missingEmptyAndPopulatedStatesRemainDistinct() = runBlocking {
        val storage = MemoryContentStorage()
        val repository = DefaultVaultIndexRepository(storage, PassThroughContentCrypto())
        assertEquals(VaultIndexRead.Missing, repository.inspect(vaultId))
        assertEquals(VaultIndexInitializationResult.Initialized, repository.initializeEmpty(vaultId) { true })
        val empty = repository.inspect(vaultId) as VaultIndexRead.Ready
        assertTrue(empty.items.isEmpty())
        val result = repository.addItem(vaultId, item("00000000000000000000000000000001")) { true }
        assertTrue(result is VaultIndexWriteResult.Added)
        val populated = repository.listItems(vaultId) as VaultIndexRead.Ready
        assertEquals(1, populated.items.size)
    }

    @Test fun failedIndexCommitPreservesPreviousValidSnapshot() = runBlocking {
        val storage = MemoryContentStorage()
        val repository = DefaultVaultIndexRepository(storage, PassThroughContentCrypto())
        repository.initializeEmpty(vaultId) { true }
        storage.failNextCommit = true
        assertEquals(VaultIndexWriteResult.Failed, repository.addItem(vaultId, item("00000000000000000000000000000001")) { true })
        val previous = repository.inspect(vaultId) as VaultIndexRead.Ready
        assertEquals(0L, previous.generation)
        assertTrue(previous.items.isEmpty())
        assertEquals(setOf(0L), storage.snapshots.keys)
    }

    @Test fun sessionExpiryAtIndexCommitLeavesPreviousSnapshotAndNoNewGeneration() = runBlocking {
        val storage = MemoryContentStorage()
        val repository = DefaultVaultIndexRepository(storage, PassThroughContentCrypto())
        assertEquals(VaultIndexInitializationResult.AuthorizationExpired, repository.initializeEmpty(vaultId) { false })
        assertTrue(storage.snapshots.isEmpty())

        repository.initializeEmpty(vaultId) { true }
        assertEquals(
            VaultIndexWriteResult.AuthorizationExpired,
            repository.addItem(vaultId, item("00000000000000000000000000000001")) { false },
        )
        assertEquals(setOf(0L), storage.snapshots.keys)
        assertEquals(0, (repository.inspect(vaultId) as VaultIndexRead.Ready).items.size)
    }

    @Test fun corruptLatestDoesNotFallBackToAnOlderValidGeneration() = runBlocking {
        val storage = MemoryContentStorage()
        val repository = DefaultVaultIndexRepository(storage, PassThroughContentCrypto())
        assertEquals(VaultIndexInitializationResult.Initialized, repository.initializeEmpty(vaultId) { true })
        assertTrue(repository.addItem(vaultId, item("00000000000000000000000000000001")) { true } is VaultIndexWriteResult.Added)
        assertTrue(storage.snapshots.containsKey(1L))
        storage.snapshots.getValue(1).let { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertEquals(VaultIndexRead.Corrupt, repository.inspect(vaultId))
    }

    @Test fun missingIndexWithObjectsIsNotReportedAsEmptyAndCannotBeSilentlyReset() = runBlocking {
        val storage = MemoryContentStorage().apply {
            hasObjects = true
            objectIds = setOf(VaultItemId("00000000000000000000000000000009"))
        }
        val repository = DefaultVaultIndexRepository(storage, PassThroughContentCrypto())
        assertEquals(VaultIndexRead.ContentWithoutIndex(1, 0), repository.inspect(vaultId))
        assertEquals(VaultIndexInitializationResult.ObjectsAlreadyPresent, repository.initializeEmpty(vaultId) { true })
        assertTrue(storage.snapshots.isEmpty())
    }

    @Test fun missingOrphanAndUnfinishedObjectsAreReportedWithoutRepair() = runBlocking {
        val storage = MemoryContentStorage()
        val repository = DefaultVaultIndexRepository(storage, PassThroughContentCrypto())
        repository.initializeEmpty(vaultId) { true }
        repository.addItem(vaultId, item("00000000000000000000000000000001")) { true }
        storage.objectIds = setOf(VaultItemId("00000000000000000000000000000002"))
        storage.unfinished = 3
        storage.unrecognized = 1

        val ready = repository.inspect(vaultId) as VaultIndexRead.Ready
        assertEquals(1, ready.contentDiagnostics?.missingContent)
        assertEquals(2, ready.contentDiagnostics?.unindexedObjects)
        assertEquals(3, ready.contentDiagnostics?.unfinishedObjects)
        assertEquals(setOf(0L, 1L), storage.snapshots.keys)
    }

    @Test fun unsupportedOuterIndexVersionIsDistinct() = runBlocking {
        val storage = MemoryContentStorage()
        val repository = DefaultVaultIndexRepository(storage, PassThroughContentCrypto())
        repository.initializeEmpty(vaultId) { true }
        storage.snapshots[0]!![4] = 88
        assertEquals(VaultIndexRead.UnsupportedVersion(88), repository.inspect(vaultId))
    }

    private fun item(id: String) = VaultItem(
        id = VaultItemId(id), originalFilename = "report.pdf", originalMimeType = "application/pdf",
        originalSizeBytes = 128, importedAtEpochMillis = 1_000,
        objectFormatVersion = VaultContentObjectCodec.VERSION,
        objectSizeBytes = VaultContentObjectCodec.HEADER_BYTES + 128 + 16L,
        encryptedItemKey = byteArrayOf(1, 2, 3),
    )

    private class PassThroughContentCrypto : VaultContentCrypto {
        override suspend fun encryptIndex(vaultId: VaultId, generation: Long, plaintext: ByteArray) =
            VaultContentCryptoResult.Success(VaultIndexEnvelopeCodec.encode(generation, plaintext))
        override suspend fun decryptIndex(vaultId: VaultId, generation: Long, encoded: ByteArray) =
            when (val decoded = VaultIndexEnvelopeCodec.decode(encoded)) {
                is VaultIndexEnvelopeCodec.DecodeResult.Valid -> VaultContentCryptoResult.Success(decoded.value.encryptedRecord)
                is VaultIndexEnvelopeCodec.DecodeResult.Unsupported -> VaultContentCryptoResult.UnsupportedVersion(decoded.version)
                VaultIndexEnvelopeCodec.DecodeResult.Invalid -> VaultContentCryptoResult.AuthenticationFailed
            }
        override suspend fun encryptObject(
            vaultId: VaultId, itemId: VaultItemId, source: InputStream, destination: OutputStream,
            expectedSourceSize: Long?, authorizationCheckpoint: suspend () -> Boolean, onProgress: (Long) -> Unit,
        ): VaultContentCryptoResult<VaultContentEncryptionResult> = VaultContentCryptoResult.OperationFailed
        override suspend fun verifyObject(
            vaultId: VaultId, item: VaultItem, source: InputStream, authorizationCheckpoint: suspend () -> Boolean,
        ): VaultContentCryptoResult<Boolean> = VaultContentCryptoResult.OperationFailed
    }

    private class MemoryContentStorage : VaultContentStorage {
        val snapshots = sortedMapOf<Long, ByteArray>()
        var hasObjects = false
        var objectIds: Set<VaultItemId> = emptySet()
        var unfinished = 0
        var unrecognized = 0
        var failNextCommit = false
        override suspend fun initializeContentDirectories() = VaultIndexFileCommitResult.Created
        override suspend fun inspectIndexFiles(): VaultIndexFiles =
            if (snapshots.isEmpty()) VaultIndexFiles.NoIndex(false, false, hasObjects)
            else VaultIndexFiles.Present(snapshots.keys.toList(), false, false, hasObjects)
        override suspend fun readIndexFile(generation: Long): ByteArray? = snapshots[generation]?.copyOf()
        override suspend fun commitIndexFileAtomically(
            generation: Long, bytes: ByteArray, authorizationCheckpoint: suspend () -> Boolean,
        ): VaultIndexFileCommitResult {
            if (!authorizationCheckpoint()) return VaultIndexFileCommitResult.AuthorizationExpired
            if (failNextCommit) { failNextCommit = false; return VaultIndexFileCommitResult.WriteFailed }
            if (snapshots.containsKey(generation)) return VaultIndexFileCommitResult.Conflict
            snapshots[generation] = bytes.copyOf()
            return VaultIndexFileCommitResult.Created
        }
        override suspend fun writeObjectAtomically(
            itemId: VaultItemId, authorizationCheckpoint: suspend () -> Boolean,
            writer: suspend (OutputStream) -> VaultObjectWriteInfo,
        ): VaultObjectCommitResult = VaultObjectCommitResult.WriteFailed
        override suspend fun openObject(itemId: VaultItemId): InputStream? = null
        override suspend fun inspectObjectDirectory() = VaultObjectDirectoryRead.Available(objectIds, unfinished, unrecognized)
    }
}
