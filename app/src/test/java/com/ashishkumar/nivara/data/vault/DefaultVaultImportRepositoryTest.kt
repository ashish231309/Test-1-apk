package com.ashishkumar.nivara.data.vault

import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.content.VaultContentCrypto
import com.ashishkumar.nivara.domain.vault.content.VaultContentCryptoResult
import com.ashishkumar.nivara.domain.vault.content.VaultContentEncryptionResult
import com.ashishkumar.nivara.domain.vault.content.VaultContentObjectCodec
import com.ashishkumar.nivara.domain.vault.content.VaultContentStorage
import com.ashishkumar.nivara.domain.vault.content.VaultImportResult
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRead
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRepository
import com.ashishkumar.nivara.domain.vault.content.VaultIndexWriteResult
import com.ashishkumar.nivara.domain.vault.content.VaultItem
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import com.ashishkumar.nivara.domain.vault.content.VaultObjectCommitResult
import com.ashishkumar.nivara.domain.vault.content.VaultObjectWriteInfo
import com.ashishkumar.nivara.domain.vault.content.VaultSourceDocumentProvider
import com.ashishkumar.nivara.domain.vault.content.VaultSourceMetadata
import com.ashishkumar.nivara.domain.vault.content.VaultSourceMetadataRead
import com.ashishkumar.nivara.domain.vault.content.VaultSourceOpenResult
import com.ashishkumar.nivara.domain.vault.content.VaultSourceSelectionId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class DefaultVaultImportRepositoryTest {
    private val vaultId = VaultId("00112233445566778899aabbccddeeff")
    private val sourceId = VaultSourceSelectionId("11112222333344445555666677778888")

    @Test fun successfulImportFinalizesVerifiesThenIndexesOneItem() = runBlocking {
        val fixture = Fixture()
        val sourceBytes = ByteArray(150_123) { (it and 0xff).toByte() }
        fixture.sources.bytes = sourceBytes
        fixture.sources.metadataValue = VaultSourceMetadata("document.pdf", "application/pdf", sourceBytes.size.toLong())
        val sourceSnapshot = sourceBytes.copyOf()
        val result = fixture.repository.importDocument(sourceId, vaultId, { true }, { _, _ -> })
        assertTrue(result is VaultImportResult.Imported)
        assertTrue(fixture.storage.finalObject.isNotEmpty())
        assertEquals(1, fixture.index.items.size)
        assertEquals("document.pdf", fixture.index.items.single().originalFilename)
        assertTrue(fixture.index.items.single().contentDigestSha256!!.contentEquals(java.security.MessageDigest.getInstance("SHA-256").digest(sourceBytes)))
        assertTrue(fixture.sources.discarded)
        assertTrue(sourceSnapshot.contentEquals(fixture.sources.bytes))
        sourceSnapshot.fill(0)
    }

    @Test fun expiredBeforeImportReadsNoSource() = runBlocking {
        val fixture = Fixture()
        val result = fixture.repository.importDocument(sourceId, vaultId, { false }, { _, _ -> })
        assertEquals(VaultImportResult.AuthorizationExpired, result)
        assertFalse(fixture.sources.opened)
        assertFalse(fixture.storage.finalized)
        assertTrue(fixture.sources.discarded)
    }

    @Test fun expiryDuringStreamingLeavesOnlyNonImportedTemporaryOutput() = runBlocking {
        val fixture = Fixture()
        fixture.sources.bytes = ByteArray(200_000)
        var checks = 0
        val result = fixture.repository.importDocument(sourceId, vaultId, { ++checks < 3 }, { _, _ -> })
        assertEquals(VaultImportResult.AuthorizationExpired, result)
        assertFalse(fixture.storage.finalized)
        assertTrue(fixture.storage.temporaryWasDiscarded)
        assertTrue(fixture.index.items.isEmpty())
    }

    @Test fun expiryAfterFinalizationLeavesUnindexedObjectWithoutReconstruction() = runBlocking {
        val fixture = Fixture()
        fixture.sources.bytes = ByteArray(128)
        var checks = 0
        val result = fixture.repository.importDocument(sourceId, vaultId, { ++checks < 6 }, { _, _ -> })
        assertEquals(VaultImportResult.AuthorizationExpired, result)
        assertTrue(fixture.storage.finalized)
        assertTrue(fixture.index.items.isEmpty())
        assertEquals(0, fixture.index.addCalls)
    }

    @Test fun failedIndexCommitPreservesFinalObjectAsUnindexedOrphan() = runBlocking {
        val fixture = Fixture()
        fixture.sources.bytes = ByteArray(128)
        fixture.index.failCommit = true
        val result = fixture.repository.importDocument(sourceId, vaultId, { true }, { _, _ -> })
        assertEquals(VaultImportResult.IndexWriteFailed, result)
        assertTrue(fixture.storage.finalized)
        assertTrue(fixture.index.items.isEmpty())
        assertEquals(1, fixture.index.addCalls)
    }

    @Test fun pathLikeSourceNameIsRejectedBeforeDestinationWrite() = runBlocking {
        val fixture = Fixture()
        fixture.sources.metadataValue = VaultSourceMetadata("../secret.txt", "text/plain", 12)
        assertEquals(VaultImportResult.SourceMetadataInvalid, fixture.repository.importDocument(sourceId, vaultId, { true }, { _, _ -> }))
        assertFalse(fixture.storage.finalized)
    }

    private class Fixture {
        val sources = FakeSources()
        val storage = FakeObjectStorage()
        val index = FakeIndex()
        val repository = DefaultVaultImportRepository(
            sources, storage, FakeCrypto(), index, object : SecureRandomSource {
                private var next = 0
                override fun generateBytes(size: Int) = ByteArray(size) { (next++ and 0xff).toByte() }
            },
        )
    }

    private class FakeSources : VaultSourceDocumentProvider {
        var bytes = ByteArray(128)
        var metadataValue = VaultSourceMetadata("document.pdf", "application/pdf", 128)
        var opened = false
        var discarded = false
        override suspend fun metadata(sourceId: VaultSourceSelectionId) = VaultSourceMetadataRead.Available(metadataValue)
        override suspend fun open(sourceId: VaultSourceSelectionId): VaultSourceOpenResult {
            opened = true
            return VaultSourceOpenResult.Opened(ByteArrayInputStream(bytes))
        }
        override fun discard(sourceId: VaultSourceSelectionId) { discarded = true }
    }

    private class FakeObjectStorage : VaultContentStorage {
        var finalized = false
        var temporaryWasDiscarded = false
        var finalObject = byteArrayOf()
        override suspend fun initializeContentDirectories() = com.ashishkumar.nivara.domain.vault.content.VaultIndexFileCommitResult.Created
        override suspend fun inspectIndexFiles() = com.ashishkumar.nivara.domain.vault.content.VaultIndexFiles.NoIndex(false, false, false)
        override suspend fun readIndexFile(generation: Long): ByteArray? = null
        override suspend fun commitIndexFileAtomically(
            generation: Long, bytes: ByteArray, authorizationCheckpoint: suspend () -> Boolean,
        ) = com.ashishkumar.nivara.domain.vault.content.VaultIndexFileCommitResult.WriteFailed
        override suspend fun writeObjectAtomically(
            itemId: VaultItemId,
            authorizationCheckpoint: suspend () -> Boolean,
            writer: suspend (OutputStream) -> VaultObjectWriteInfo,
        ): VaultObjectCommitResult {
            val temp = ByteArrayOutputStream()
            return try {
                val result = writer(temp)
                val bytes = temp.toByteArray()
                if (!authorizationCheckpoint()) {
                    temporaryWasDiscarded = true
                    return VaultObjectCommitResult.AuthorizationExpired
                }
                if (bytes.size.toLong() != result.expectedObjectBytes) {
                    temporaryWasDiscarded = true
                    VaultObjectCommitResult.VerificationFailed
                } else {
                    finalObject = bytes
                    finalized = true
                    VaultObjectCommitResult.Created(bytes.size.toLong())
                }
            } catch (_: Exception) {
                temporaryWasDiscarded = true
                VaultObjectCommitResult.WriteFailed
            }
        }
        override suspend fun openObject(itemId: VaultItemId): InputStream? =
            if (finalized) ByteArrayInputStream(finalObject) else null
    }

    private class FakeCrypto : VaultContentCrypto {
        override suspend fun encryptIndex(vaultId: VaultId, generation: Long, plaintext: ByteArray) =
            VaultContentCryptoResult.OperationFailed as VaultContentCryptoResult<ByteArray>
        override suspend fun decryptIndex(vaultId: VaultId, generation: Long, encoded: ByteArray) =
            VaultContentCryptoResult.OperationFailed as VaultContentCryptoResult<ByteArray>

        override suspend fun encryptObject(
            vaultId: VaultId, itemId: VaultItemId, source: InputStream, destination: OutputStream,
            expectedSourceSize: Long?, authorizationCheckpoint: suspend () -> Boolean, onProgress: (Long) -> Unit,
        ): VaultContentCryptoResult<VaultContentEncryptionResult> {
            if (!authorizationCheckpoint()) return VaultContentCryptoResult.AuthorizationExpired
            destination.write(VaultContentObjectCodec.encodeHeader(itemId, ByteArray(12) { 1 }))
            val buffer = ByteArray(4096)
            var total = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (!authorizationCheckpoint()) return VaultContentCryptoResult.AuthorizationExpired
                destination.write(buffer, 0, count)
                total += count
                onProgress(total)
            }
            buffer.fill(0)
            if (expectedSourceSize != null && expectedSourceSize != total) return VaultContentCryptoResult.SourceSizeMismatch
            destination.write(ByteArray(16))
            val objectSize = VaultContentObjectCodec.HEADER_BYTES + total + 16
            return VaultContentCryptoResult.Success(VaultContentEncryptionResult(total, objectSize, byteArrayOf(1, 2, 3)))
        }

        override suspend fun verifyObject(
            vaultId: VaultId, item: VaultItem, source: InputStream, authorizationCheckpoint: suspend () -> Boolean,
        ): VaultContentCryptoResult<Boolean> = if (authorizationCheckpoint()) {
            VaultContentCryptoResult.Success(true)
        } else VaultContentCryptoResult.AuthorizationExpired
    }

    private class FakeIndex : VaultIndexRepository {
        val items = mutableListOf<VaultItem>()
        var addCalls = 0
        var failCommit = false
        override suspend fun inspect(vaultId: VaultId): VaultIndexRead = VaultIndexRead.Ready(0, items.toList())
        override suspend fun initializeEmpty(vaultId: VaultId, authorizationCheckpoint: suspend () -> Boolean) =
            com.ashishkumar.nivara.domain.vault.content.VaultIndexInitializationResult.Initialized
        override suspend fun listItems(vaultId: VaultId) = inspect(vaultId)
        override suspend fun addItem(
            vaultId: VaultId, item: VaultItem, authorizationCheckpoint: suspend () -> Boolean,
        ): VaultIndexWriteResult {
            addCalls++
            if (!authorizationCheckpoint()) return VaultIndexWriteResult.AuthorizationExpired
            if (failCommit) return VaultIndexWriteResult.Failed
            items += item
            return VaultIndexWriteResult.Added(item, items.size.toLong())
        }
    }
}
