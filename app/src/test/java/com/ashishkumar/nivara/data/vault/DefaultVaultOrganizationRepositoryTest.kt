package com.ashishkumar.nivara.data.vault

import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.VaultStatus
import com.ashishkumar.nivara.domain.vault.content.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

class DefaultVaultOrganizationRepositoryTest {
    private val vaultId = VaultId("00112233445566778899aabbccddeeff")
    private val item = VaultItem(
        VaultItemId("00000000000000000000000000000001"), "one.txt", "text/plain", 1, 1,
        VaultContentObjectCodec.VERSION, VaultContentObjectCodec.HEADER_BYTES + 17L, byteArrayOf(1),
    )

    @Test fun emptyStateIsOnlyInferredForMissingOrganizationAndMutationsAreSessionGated() = runBlocking {
        val h = Harness()
        assertEquals(VaultOrganizationRead.Ready(VaultOrganizationSnapshot(0, emptyList())), h.repo.inspect(vaultId))
        assertEquals(VaultAlbumMutationResult.AuthorizationExpired,
            h.repo.createAlbum(vaultId, "private") { false })
        assertTrue(h.storage.records.isEmpty())
        assertTrue(h.storage.deletedObjects.isEmpty())
        assertEquals(VaultAlbumMutationResult.Changed::class.java,
            h.repo.createAlbum(vaultId, "  Private  ") { true }::class.java)
        assertEquals("Private", (h.repo.inspect(vaultId) as VaultOrganizationRead.Ready).snapshot.albums.single().name)
    }

    @Test fun organizationStorageFailuresAndUnexpectedEntriesNeverBecomeEmpty() = runBlocking {
        val h = Harness()
        h.storage.storageUnavailable = true
        assertEquals(VaultOrganizationRead.Unavailable, h.repo.inspect(vaultId))
        h.storage.storageUnavailable = false
        h.storage.accessDenied = true
        assertEquals(VaultOrganizationRead.AccessDenied, h.repo.inspect(vaultId))
        h.storage.accessDenied = false
        h.storage.unexpected = true
        assertEquals(VaultOrganizationRead.Corrupt, h.repo.inspect(vaultId))
        assertTrue(h.storage.records.isEmpty())
    }

    @Test fun idsAreRandomNamesMayRepeatAndDuplicateMembershipIsExplicitlyIdempotent() = runBlocking {
        val h = Harness()
        h.index.result = VaultIndexRead.Ready(1, listOf(item))
        val first = h.repo.createAlbum(vaultId, "Same name") { true } as VaultAlbumMutationResult.Changed
        val second = h.repo.createAlbum(vaultId, "Same name") { true } as VaultAlbumMutationResult.Changed
        assertFalse(first.albumId == second.albumId)
        val albumA = first.albumId!!
        val albumB = second.albumId!!
        assertTrue(h.repo.addMembership(vaultId, albumA, item.id) { true } is VaultAlbumMutationResult.Changed)
        assertTrue(h.repo.addMembership(vaultId, albumB, item.id) { true } is VaultAlbumMutationResult.Changed)
        assertEquals(VaultAlbumMutationResult.AlreadyMember, h.repo.addMembership(vaultId, albumA, item.id) { true })
        val snapshot = (h.repo.inspect(vaultId) as VaultOrganizationRead.Ready).snapshot
        assertEquals(listOf(item.id), snapshot.albums.single { it.id == albumA }.memberItemIds)
        assertEquals(listOf(item.id), snapshot.albums.single { it.id == albumB }.memberItemIds)
        assertTrue(h.storage.deletedObjects.isEmpty())
    }

    @Test fun newMembershipRejectsTrashedItemsButExistingReferencesRemain() = runBlocking {
        val h = Harness()
        val activeReference = (h.repo.createAlbum(vaultId, "Keeps old reference") { true } as VaultAlbumMutationResult.Changed).albumId!!
        h.index.result = VaultIndexRead.Ready(1, listOf(item))
        h.repo.addMembership(vaultId, activeReference, item.id) { true }
        val trashed = item.withLifecycle(VaultItemLifecycle.TRASHED, 99)
        val otherAlbum = (h.repo.createAlbum(vaultId, "No new trashed membership") { true } as VaultAlbumMutationResult.Changed).albumId!!
        h.index.result = VaultIndexRead.Ready(3, listOf(trashed))
        assertEquals(VaultAlbumMutationResult.ItemTrashed,
            h.repo.addMembership(vaultId, otherAlbum, item.id) { true })
        val snapshot = (h.repo.inspect(vaultId) as VaultOrganizationRead.Ready).snapshot
        assertEquals(listOf(item.id), snapshot.albums.single { it.id == activeReference }.memberItemIds)
        assertTrue(snapshot.albums.single { it.id == otherAlbum }.memberItemIds.isEmpty())
    }

    @Test fun everyAlbumAndMembershipMutationRejectsExpiredAuthorization() = runBlocking {
        val h = Harness()
        h.index.result = VaultIndexRead.Ready(1, listOf(item))
        val albumId = (h.repo.createAlbum(vaultId, "Protected") { true } as VaultAlbumMutationResult.Changed).albumId!!
        h.repo.addMembership(vaultId, albumId, item.id) { true }
        val before = h.storage.records.mapValues { it.value.copyOf() }
        assertEquals(VaultAlbumMutationResult.AuthorizationExpired, h.repo.createAlbum(vaultId, "Denied") { false })
        assertEquals(VaultAlbumMutationResult.AuthorizationExpired, h.repo.renameAlbum(vaultId, albumId, "Denied") { false })
        assertEquals(VaultAlbumMutationResult.AuthorizationExpired, h.repo.deleteAlbum(vaultId, albumId) { false })
        assertEquals(VaultAlbumMutationResult.AuthorizationExpired, h.repo.addMembership(vaultId, albumId, item.id) { false })
        assertEquals(VaultAlbumMutationResult.AuthorizationExpired, h.repo.removeMembership(vaultId, albumId, item.id) { false })
        assertTrue(before.keys == h.storage.records.keys)
        before.forEach { (generation, bytes) -> assertTrue(bytes.contentEquals(h.storage.records.getValue(generation))) }
        before.values.forEach { it.fill(0) }
    }

    @Test fun deletingAlbumAndRemovingMembershipOnlyChangeOrganizationReferences() = runBlocking {
        val h = Harness()
        h.index.result = VaultIndexRead.Ready(1, listOf(item))
        val created = h.repo.createAlbum(vaultId, "Empty is allowed") { true } as VaultAlbumMutationResult.Changed
        val albumId = created.albumId!!
        assertEquals(0, created.snapshot.albums.single().memberItemIds.size)
        h.repo.addMembership(vaultId, albumId, item.id) { true }
        val trashedItem = item.withLifecycle(VaultItemLifecycle.TRASHED, 77)
        h.index.result = VaultIndexRead.Ready(2, listOf(trashedItem))
        val objectWritesBefore = h.storage.objectWrites
        assertEquals(VaultAlbumMutationResult.Changed::class.java,
            h.repo.removeMembership(vaultId, albumId, item.id) { true }::class.java)
        assertEquals(VaultAlbumMutationResult.Changed::class.java,
            h.repo.deleteAlbum(vaultId, albumId) { true }::class.java)
        assertTrue((h.repo.inspect(vaultId) as VaultOrganizationRead.Ready).snapshot.albums.isEmpty())
        val remaining = (h.index.result as VaultIndexRead.Ready).items.single()
        assertEquals(trashedItem.id, remaining.id)
        assertEquals(VaultItemLifecycle.TRASHED, remaining.lifecycle)
        assertEquals(77L, remaining.trashedAtEpochMillis)
        assertEquals(objectWritesBefore, h.storage.objectWrites)
        assertTrue(h.storage.deletedObjects.isEmpty())
    }

    @Test fun staleReferencesArePreservedOnReadAndMayBeRemovedExplicitly() = runBlocking {
        val h = Harness()
        h.index.result = VaultIndexRead.Ready(1, listOf(item))
        val created = h.repo.createAlbum(vaultId, "Stale") { true } as VaultAlbumMutationResult.Changed
        val albumId = created.albumId!!
        h.repo.addMembership(vaultId, albumId, item.id) { true }
        val otherId = VaultItemId("00000000000000000000000000000002")
        h.storage.replaceLatest(
            VaultOrganizationSnapshot(3, listOf(VaultAlbum(albumId, "Stale", listOf(item.id, otherId)))),
        )
        h.index.result = VaultIndexRead.Ready(4, emptyList())
        val album = (h.repo.inspect(vaultId) as VaultOrganizationRead.Ready).snapshot.albums.single()
        assertEquals(listOf(item.id, otherId), album.memberItemIds)
        assertEquals(VaultAlbumMutationResult.Changed::class.java,
            h.repo.removeMembership(vaultId, albumId, otherId) { true }::class.java)
        assertEquals(listOf(item.id), (h.repo.inspect(vaultId) as VaultOrganizationRead.Ready).snapshot.albums.single().memberItemIds)
        assertTrue(h.storage.deletedObjects.isEmpty())
    }

    @Test fun unreadableIndexBlocksNewMembershipButDoesNotMergeFailureDomains() = runBlocking {
        val h = Harness()
        val albumId = (h.repo.createAlbum(vaultId, "Can rename") { true } as VaultAlbumMutationResult.Changed).albumId!!
        h.index.result = VaultIndexRead.Corrupt
        assertEquals(VaultAlbumMutationResult.Corrupt, h.repo.addMembership(vaultId, albumId, item.id) { true })
        assertTrue(h.repo.renameAlbum(vaultId, albumId, "Still organization") { true } is VaultAlbumMutationResult.Changed)
        assertTrue(h.repo.inspect(vaultId) is VaultOrganizationRead.Ready)
    }

    @Test fun failedOrExpiredCommitKeepsPriorAuthenticatedGeneration() = runBlocking {
        val h = Harness()
        val albumId = (h.repo.createAlbum(vaultId, "Original") { true } as VaultAlbumMutationResult.Changed).albumId!!
        h.storage.failNextCommit = true
        assertEquals(VaultAlbumMutationResult.WriteFailed,
            h.repo.renameAlbum(vaultId, albumId, "Never committed") { true })
        assertEquals("Original", (h.repo.inspect(vaultId) as VaultOrganizationRead.Ready).snapshot.albums.single().name)
        assertTrue(h.storage.records.containsKey(1L))
        assertFalse(h.storage.records.containsKey(2L))

        assertEquals(VaultAlbumMutationResult.AuthorizationExpired,
            h.repo.renameAlbum(vaultId, albumId, "Expired") { false })
        assertEquals("Original", (h.repo.inspect(vaultId) as VaultOrganizationRead.Ready).snapshot.albums.single().name)
    }

    @Test fun readbackAuthenticationFailureRollsBackOnlyTheUnverifiedGeneration() = runBlocking {
        val h = Harness()
        val albumId = (h.repo.createAlbum(vaultId, "Prior") { true } as VaultAlbumMutationResult.Changed).albumId!!
        h.storage.tamperNextCommit = true
        assertEquals(VaultAlbumMutationResult.Corrupt,
            h.repo.renameAlbum(vaultId, albumId, "Unverified") { true })
        assertEquals(setOf(1L), h.storage.records.keys)
        assertEquals("Prior", (h.repo.inspect(vaultId) as VaultOrganizationRead.Ready).snapshot.albums.single().name)
        assertEquals(0, h.storage.pruneCalls)
    }

    @Test fun failedRollbackLeavesPriorGenerationAndCorruptionVisibleRatherThanEmpty() = runBlocking {
        val h = Harness()
        val albumId = (h.repo.createAlbum(vaultId, "Prior") { true } as VaultAlbumMutationResult.Changed).albumId!!
        h.storage.tamperNextCommit = true
        h.storage.failDiscard = true
        assertEquals(VaultAlbumMutationResult.Corrupt,
            h.repo.renameAlbum(vaultId, albumId, "Unverified") { true })
        assertEquals(setOf(1L, 2L), h.storage.records.keys)
        assertEquals(VaultOrganizationRead.Corrupt, h.repo.inspect(vaultId))
    }

    @Test fun expiryDuringPostVerificationPruningIsNotReportedAsSuccess() = runBlocking {
        val h = Harness()
        val albumId = (h.repo.createAlbum(vaultId, "Prior") { true } as VaultAlbumMutationResult.Changed).albumId!!
        var checks = 0
        val result = h.repo.renameAlbum(vaultId, albumId, "Committed before expiry") {
            checks++
            checks < 7
        }
        assertEquals(VaultAlbumMutationResult.AuthorizationExpired, result)
        assertEquals("Committed before expiry", (h.repo.inspect(vaultId) as VaultOrganizationRead.Ready).snapshot.albums.single().name)
        assertTrue(checks >= 7)
    }

    @Test fun newestCorruptGenerationFailsClosedAndNeverFallsBackOrAppearsEmpty() = runBlocking {
        val h = Harness()
        val albumId = (h.repo.createAlbum(vaultId, "Old") { true } as VaultAlbumMutationResult.Changed).albumId!!
        h.repo.renameAlbum(vaultId, albumId, "Latest") { true }
        h.storage.records.getValue(2).let { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertEquals(VaultOrganizationRead.Corrupt, h.repo.inspect(vaultId))
        assertTrue(h.storage.records.containsKey(1L))
        assertTrue(h.storage.records.containsKey(2L))
    }

    @Test fun unsupportedAndPendingOrganizationStatesAreDistinctFromEmpty() = runBlocking {
        val h = Harness()
        h.storage.directoryMissing = false
        h.storage.records[1L] = byteArrayOf(0x4e, 0x56, 0x4f, 0x45, 78, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1)
        assertEquals(VaultOrganizationRead.UnsupportedVersion(78), h.repo.inspect(vaultId))
        h.storage.pending = true
        assertEquals(VaultOrganizationRead.Corrupt, h.repo.inspect(vaultId))
    }

    @Test fun verifiedWritesPruneOnlyOlderGenerationsAndRetainTwoForCrashRecovery() = runBlocking {
        val h = Harness()
        val albumId = (h.repo.createAlbum(vaultId, "One") { true } as VaultAlbumMutationResult.Changed).albumId!!
        h.repo.renameAlbum(vaultId, albumId, "Two") { true }
        h.repo.renameAlbum(vaultId, albumId, "Three") { true }
        assertEquals(setOf(2L, 3L), h.storage.records.keys)
        assertEquals("Three", (h.repo.inspect(vaultId) as VaultOrganizationRead.Ready).snapshot.albums.single().name)
    }

    @Test fun malformedNamesAndMissingIndexedItemsDoNotCreateMemberships() = runBlocking {
        val h = Harness()
        val albumId = (h.repo.createAlbum(vaultId, "Allowed") { true } as VaultAlbumMutationResult.Changed).albumId!!
        assertTrue(h.repo.renameAlbum(vaultId, albumId, " \u0000 ") { true } is VaultAlbumMutationResult.InvalidName)
        h.index.result = VaultIndexRead.Ready(2, emptyList())
        assertEquals(VaultAlbumMutationResult.ItemNotFound, h.repo.addMembership(vaultId, albumId, item.id) { true })
        assertTrue((h.repo.inspect(vaultId) as VaultOrganizationRead.Ready).snapshot.albums.single().memberItemIds.isEmpty())
    }

    private class Harness {
        val storage = MemoryStorage()
        val index = FakeIndex()
        val random = CounterRandom()
        val repo = DefaultVaultOrganizationRepository(storage, PlainOrganizationCrypto(), index, random)
    }

    private class PlainOrganizationCrypto : VaultContentCrypto {
        override suspend fun encryptIndex(vaultId: VaultId, generation: Long, plaintext: ByteArray) =
            VaultContentCryptoResult.Success(plaintext.copyOf())
        override suspend fun decryptIndex(vaultId: VaultId, generation: Long, encoded: ByteArray) =
            VaultContentCryptoResult.Success(encoded.copyOf())
        override suspend fun encryptObject(
            vaultId: VaultId, itemId: VaultItemId, source: InputStream, destination: OutputStream,
            expectedSourceSize: Long?, authorizationCheckpoint: suspend () -> Boolean, onProgress: (Long) -> Unit,
        ): VaultContentCryptoResult<VaultContentEncryptionResult> = VaultContentCryptoResult.OperationFailed
        override suspend fun verifyObject(
            vaultId: VaultId, item: VaultItem, source: InputStream, authorizationCheckpoint: suspend () -> Boolean,
        ): VaultContentCryptoResult<Boolean> = VaultContentCryptoResult.OperationFailed
        override suspend fun encryptOrganization(vaultId: VaultId, generation: Long, plaintext: ByteArray) =
            VaultContentCryptoResult.Success(VaultOrganizationEnvelopeCodec.encode(generation, plaintext))
        override suspend fun decryptOrganization(vaultId: VaultId, generation: Long, encoded: ByteArray) = when (
            val result = VaultOrganizationEnvelopeCodec.decode(encoded)
        ) {
            is VaultOrganizationEnvelopeCodec.DecodeResult.Valid -> if (result.record.generation == generation) {
                VaultContentCryptoResult.Success(result.record.encryptedEnvelope.copyOf())
            } else VaultContentCryptoResult.AuthenticationFailed
            is VaultOrganizationEnvelopeCodec.DecodeResult.Unsupported -> VaultContentCryptoResult.UnsupportedVersion(result.version)
            VaultOrganizationEnvelopeCodec.DecodeResult.Invalid -> VaultContentCryptoResult.AuthenticationFailed
        }
    }

    private class MemoryStorage : VaultContentStorage {
        val records = sortedMapOf<Long, ByteArray>()
        val deletedObjects = mutableListOf<VaultItemId>()
        var objectWrites = 0
        var directoryMissing = true
        var pending = false
        var unexpected = false
        var storageUnavailable = false
        var accessDenied = false
        var failNextCommit = false
        var tamperNextCommit = false
        var failDiscard = false
        var pruneCalls = 0

        override suspend fun initializeContentDirectories() = VaultIndexFileCommitResult.Created
        override suspend fun inspectIndexFiles(): VaultIndexFiles = VaultIndexFiles.NoIndex(false, false, false)
        override suspend fun readIndexFile(generation: Long): ByteArray? = null
        override suspend fun commitIndexFileAtomically(
            generation: Long, bytes: ByteArray, authorizationCheckpoint: suspend () -> Boolean,
        ) = VaultIndexFileCommitResult.WriteFailed
        override suspend fun writeObjectAtomically(
            itemId: VaultItemId, authorizationCheckpoint: suspend () -> Boolean,
            writer: suspend (OutputStream) -> VaultObjectWriteInfo,
        ): VaultObjectCommitResult { objectWrites++; return VaultObjectCommitResult.WriteFailed }
        override suspend fun openObject(itemId: VaultItemId): InputStream? = null

        override suspend fun inspectOrganizationFiles(): VaultOrganizationFiles {
            if (storageUnavailable) return VaultOrganizationFiles.Unavailable
            if (accessDenied) return VaultOrganizationFiles.AccessDenied
            if (directoryMissing && records.isEmpty()) return VaultOrganizationFiles.Missing
            return VaultOrganizationFiles.Present(records.keys.toList(), pending, unexpected)
        }
        override suspend fun readOrganizationFile(generation: Long): ByteArray? = records[generation]?.copyOf()
        override suspend fun commitOrganizationFileAtomically(
            generation: Long, bytes: ByteArray, authorizationCheckpoint: suspend () -> Boolean,
        ): VaultOrganizationStorageResult {
            if (!authorizationCheckpoint()) return VaultOrganizationStorageResult.AuthorizationExpired
            if (failNextCommit) { failNextCommit = false; return VaultOrganizationStorageResult.WriteFailed }
            if (generation != (records.keys.maxOrNull() ?: 0L) + 1 || records.containsKey(generation)) {
                return VaultOrganizationStorageResult.Conflict
            }
            records[generation] = bytes.copyOf()
            directoryMissing = false
            if (tamperNextCommit) {
                tamperNextCommit = false
                records.getValue(generation).let { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            }
            return VaultOrganizationStorageResult.Created
        }
        override suspend fun pruneOrganizationFiles(
            keepGenerations: Set<Long>, authorizationCheckpoint: suspend () -> Boolean,
        ): VaultOrganizationStorageResult {
            if (!authorizationCheckpoint()) return VaultOrganizationStorageResult.AuthorizationExpired
            pruneCalls++
            records.keys.toList().filter { it !in keepGenerations }.forEach { records.remove(it) }
            return VaultOrganizationStorageResult.Created
        }
        override suspend fun discardOrganizationFile(generation: Long): VaultOrganizationStorageResult {
            if (failDiscard) return VaultOrganizationStorageResult.CleanupFailed
            records.remove(generation)
            return VaultOrganizationStorageResult.Created
        }
        fun replaceLatest(snapshot: VaultOrganizationSnapshot) {
            val encoded = VaultOrganizationCodec.encode(VaultId("00112233445566778899aabbccddeeff"), snapshot)
            records[snapshot.generation] = VaultOrganizationEnvelopeCodec.encode(snapshot.generation, encoded)
            encoded.fill(0)
            directoryMissing = false
        }
    }

    private class FakeIndex : VaultIndexRepository {
        var result: VaultIndexRead = VaultIndexRead.Missing
        override suspend fun inspect(vaultId: VaultId): VaultIndexRead = result
        override suspend fun initializeEmpty(vaultId: VaultId, authorizationCheckpoint: suspend () -> Boolean) =
            VaultIndexInitializationResult.Unavailable
        override suspend fun listItems(vaultId: VaultId): VaultIndexRead = result
        override suspend fun addItem(vaultId: VaultId, item: VaultItem, authorizationCheckpoint: suspend () -> Boolean) =
            VaultIndexWriteResult.Failed
    }

    private class CounterRandom : SecureRandomSource {
        private var next = 1
        override fun generateBytes(size: Int): ByteArray = ByteArray(size) { next++.toByte() }
    }
}
