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
import com.ashishkumar.nivara.domain.vault.content.VaultItemLifecycle
import com.ashishkumar.nivara.domain.vault.content.VaultItemStateMutationResult
import com.ashishkumar.nivara.domain.security.TimeProvider
import com.ashishkumar.nivara.domain.vault.content.VaultObjectCommitResult
import com.ashishkumar.nivara.domain.vault.content.VaultObjectDirectoryRead
import com.ashishkumar.nivara.domain.vault.content.VaultObjectWriteInfo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val initialized = repository.initializeEmpty(vaultId) { true }
        assertEquals(VaultIndexInitializationResult.Initialized, initialized)
        assertTrue(repository.addItem(vaultId, item("00000000000000000000000000000001")) { true } is VaultIndexWriteResult.Added)
        assertTrue(storage.snapshots.containsKey(1L))
        storage.snapshots.getValue(1).let { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertEquals(VaultIndexRead.Corrupt, repository.inspect(vaultId))
    }

    @Test fun trashRestorePreserveIdentityMetadataAndDigestAndCommitVerifiedTwoSlotsWithoutObjectScan() = runTest {
        var now = 1_700_000_000_123L
        val storage = MemoryContentStorage()
        val repository = DefaultVaultIndexRepository(storage, PassThroughContentCrypto(), TimeProvider { now })
        assertEquals(VaultIndexInitializationResult.Initialized, repository.initializeEmpty(vaultId) { true })
        val digest = ByteArray(VaultItem.SHA_256_BYTES) { (it * 7).toByte() }
        val source = item("00000000000000000000000000000001")
        val indexed = if (digest.isEmpty()) source else VaultItem(
            source.id, source.originalFilename, source.originalMimeType, source.originalSizeBytes,
            source.importedAtEpochMillis, source.objectFormatVersion, source.objectSizeBytes,
            source.encryptedItemKey, contentDigestSha256 = digest,
        )
        assertTrue(repository.addItem(vaultId, indexed) { true } is VaultIndexWriteResult.Added)
        val pre = (repository.inspect(vaultId) as VaultIndexRead.Ready).items.single()
        val directoryScans = storage.objectDirectoryReads
        val moved = repository.moveToTrash(vaultId, pre.id) { true } as VaultItemStateMutationResult.Changed
        assertEquals(pre.id, moved.item.id)
        assertEquals(VaultItemLifecycle.TRASHED, moved.item.lifecycle)
        assertEquals(now, moved.item.trashedAtEpochMillis)
        assertEquals(pre.originalFilename, moved.item.originalFilename)
        assertEquals(pre.originalMimeType, moved.item.originalMimeType)
        assertEquals(pre.originalSizeBytes, moved.item.originalSizeBytes)
        assertEquals(pre.importedAtEpochMillis, moved.item.importedAtEpochMillis)
        assertEquals(pre.objectFormatVersion, moved.item.objectFormatVersion)
        assertEquals(pre.objectSizeBytes, moved.item.objectSizeBytes)
        assertTrue(pre.encryptedItemKey.contentEquals(moved.item.encryptedItemKey))
        assertTrue(digest.contentEquals(moved.item.contentDigestSha256!!))
        assertEquals(directoryScans, storage.objectDirectoryReads)
        assertEquals(0, storage.objectOpens)
        assertEquals(setOf(1L, 2L), storage.snapshots.keys)
        val trashedSnapshot = repository.inspect(vaultId) as VaultIndexRead.Ready
        assertEquals(setOf(pre.id), trashedSnapshot.contentDiagnostics?.missingItemIds)
        assertEquals(VaultItemLifecycle.TRASHED, trashedSnapshot.items.single().lifecycle)

        now += 10_000
        val restored = repository.restoreFromTrash(vaultId, pre.id) { true } as VaultItemStateMutationResult.Changed
        assertEquals(pre.id, restored.item.id)
        assertEquals(VaultItemLifecycle.ACTIVE, restored.item.lifecycle)
        assertEquals(null, restored.item.trashedAtEpochMillis)
        assertTrue(digest.contentEquals(restored.item.contentDigestSha256!!))
        assertEquals(setOf(2L, 3L), storage.snapshots.keys)
        val restoredMissing = repository.inspect(vaultId) as VaultIndexRead.Ready
        assertEquals(setOf(pre.id), restoredMissing.contentDiagnostics?.missingItemIds)
        assertEquals(VaultItemLifecycle.ACTIVE, restoredMissing.items.single().lifecycle)
    }

    @Test fun sameItemTrashRaceIsIdempotentAndDifferentItemsSerializeWithoutLostUpdates() = runTest {
        val storage = MemoryContentStorage()
        val repository = DefaultVaultIndexRepository(storage, PassThroughContentCrypto(), TimeProvider { 5_000L })
        repository.initializeEmpty(vaultId) { true }
        val first = item("00000000000000000000000000000001")
        val second = item("00000000000000000000000000000002")
        repository.addItem(vaultId, first) { true }
        repository.addItem(vaultId, second) { true }
        val sameRace = listOf(
            async { repository.moveToTrash(vaultId, first.id) { true } },
            async { repository.moveToTrash(vaultId, first.id) { true } },
        ).awaitAll()
        assertEquals("sameRace=${sameRace.map { it::class.simpleName }}", 1,
            sameRace.count { it is VaultItemStateMutationResult.Changed })
        assertEquals(1, sameRace.count { it == VaultItemStateMutationResult.AlreadyTrashed })
        val differentRace = listOf(
            async { repository.moveToTrash(vaultId, second.id) { true } },
            async { repository.restoreFromTrash(vaultId, first.id) { true } },
        ).awaitAll()
        assertTrue(differentRace.all { it is VaultItemStateMutationResult.Changed })
        val latest = repository.inspect(vaultId) as VaultIndexRead.Ready
        assertEquals(VaultItemLifecycle.ACTIVE, latest.items.single { it.id == first.id }.lifecycle)
        assertEquals(VaultItemLifecycle.TRASHED, latest.items.single { it.id == second.id }.lifecycle)
        assertEquals(2, storage.snapshots.size)
    }

    @Test fun authorizationWriteAndAuthenticatedReadbackFailuresKeepPreviousState() = runTest {
        val storage = MemoryContentStorage()
        val repository = DefaultVaultIndexRepository(storage, PassThroughContentCrypto(), TimeProvider { 7L })
        repository.initializeEmpty(vaultId) { true }
        val item = item("00000000000000000000000000000001")
        repository.addItem(vaultId, item) { true }
        val baseGeneration = (repository.inspect(vaultId) as VaultIndexRead.Ready).generation
        assertEquals(VaultItemStateMutationResult.AuthorizationExpired,
            repository.moveToTrash(vaultId, item.id) { false })
        assertEquals(setOf(0L, 1L), storage.snapshots.keys)
        storage.failNextCommit = true
        assertEquals(VaultItemStateMutationResult.WriteFailed,
            repository.moveToTrash(vaultId, item.id) { true })
        assertEquals(baseGeneration, (repository.inspect(vaultId) as VaultIndexRead.Ready).generation)
        assertEquals(VaultItemLifecycle.ACTIVE, (repository.inspect(vaultId) as VaultIndexRead.Ready).items.single().lifecycle)

        storage.corruptReadGeneration = baseGeneration + 1
        assertEquals(VaultItemStateMutationResult.Corrupt,
            repository.moveToTrash(vaultId, item.id) { true })
        assertEquals(setOf(0L, 1L), storage.snapshots.keys)
        assertEquals(VaultItemLifecycle.ACTIVE, (repository.inspect(vaultId) as VaultIndexRead.Ready).items.single().lifecycle)
    }

    @Test fun expiryAfterCommitRollsBackNewestGenerationAndKeepsPreviousSnapshot() = runTest {
        val storage = MemoryContentStorage()
        val repository = DefaultVaultIndexRepository(storage, PassThroughContentCrypto(), TimeProvider { 77L })
        repository.initializeEmpty(vaultId) { true }
        val item = item("00000000000000000000000000000001")
        repository.addItem(vaultId, item) { true }
        var checks = 0
        val result = repository.moveToTrash(vaultId, item.id) { ++checks < 4 }
        assertEquals(VaultItemStateMutationResult.AuthorizationExpired, result)
        assertEquals(setOf(0L, 1L), storage.snapshots.keys)
        val previous = repository.inspect(vaultId) as VaultIndexRead.Ready
        assertEquals(1L, previous.generation)
        assertEquals(VaultItemLifecycle.ACTIVE, previous.items.single().lifecycle)
    }

    @Test fun verifiedStateSurvivesOptionalPruningFailureAndNextWriteRestoresTwoSlotBound() = runTest {
        val storage = MemoryContentStorage()
        val repository = DefaultVaultIndexRepository(storage, PassThroughContentCrypto(), TimeProvider { 88L })
        repository.initializeEmpty(vaultId) { true }
        val item = item("00000000000000000000000000000001")
        repository.addItem(vaultId, item) { true }
        storage.failPrune = true
        val trashed = repository.moveToTrash(vaultId, item.id) { true } as VaultItemStateMutationResult.Changed
        assertFalse(trashed.oldGenerationsPruned)
        assertEquals(setOf(0L, 1L, 2L), storage.snapshots.keys)
        assertEquals(VaultItemLifecycle.TRASHED, (repository.inspect(vaultId) as VaultIndexRead.Ready).items.single().lifecycle)
        storage.failPrune = false
        val restored = repository.restoreFromTrash(vaultId, item.id) { true } as VaultItemStateMutationResult.Changed
        assertTrue(restored.oldGenerationsPruned)
        assertEquals(setOf(2L, 3L), storage.snapshots.keys)
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
        var failPrune = false
        var corruptReadGeneration: Long? = null
        var objectDirectoryReads = 0
        var objectOpens = 0
        override suspend fun initializeContentDirectories() = VaultIndexFileCommitResult.Created
        override suspend fun inspectIndexFiles(): VaultIndexFiles =
            if (snapshots.isEmpty()) VaultIndexFiles.NoIndex(false, false, hasObjects)
            else VaultIndexFiles.Present(snapshots.keys.toList(), false, false, hasObjects)
        override suspend fun readIndexFile(generation: Long): ByteArray? {
            val copy = snapshots[generation]?.copyOf() ?: return null
            if (corruptReadGeneration == generation) {
                corruptReadGeneration = null
                copy[copy.lastIndex] = (copy.last().toInt() xor 1).toByte()
            }
            return copy
        }
        override suspend fun pruneIndexFiles(
            keepGenerations: Set<Long>, authorizationCheckpoint: suspend () -> Boolean,
        ): VaultIndexFileCommitResult {
            if (!authorizationCheckpoint()) return VaultIndexFileCommitResult.AuthorizationExpired
            if (failPrune) return VaultIndexFileCommitResult.Unavailable
            snapshots.keys.filterNot { it in keepGenerations }.toList().forEach { snapshots.remove(it)?.fill(0) }
            return VaultIndexFileCommitResult.Created
        }
        override suspend fun discardIndexFile(generation: Long): VaultIndexFileCommitResult {
            snapshots.remove(generation)?.fill(0)
            return VaultIndexFileCommitResult.Created
        }
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
        override suspend fun openObject(itemId: VaultItemId): InputStream? { objectOpens++; return null }
        override suspend fun inspectObjectDirectory(): VaultObjectDirectoryRead {
            objectDirectoryReads++
            return VaultObjectDirectoryRead.Available(objectIds, unfinished, unrecognized)
        }
    }
}
