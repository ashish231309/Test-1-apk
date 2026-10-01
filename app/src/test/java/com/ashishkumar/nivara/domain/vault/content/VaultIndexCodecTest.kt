package com.ashishkumar.nivara.domain.vault.content

import com.ashishkumar.nivara.domain.vault.VaultId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class VaultIndexCodecTest {
    private val vaultId = VaultId("00112233445566778899aabbccddeeff")

    @Test fun encodingIsDeterministicAndCanonicalByStableId() {
        val first = item("ffffffffffffffffffffffffffffffff", "zeta.pdf")
        val second = item("00000000000000000000000000000001", "alpha.pdf")
        val forward = VaultIndexCodec.encode(vaultId, 4, listOf(first, second))
        val reverse = VaultIndexCodec.encode(vaultId, 4, listOf(second, first))
        assertTrue(forward.contentEquals(reverse))
        val decoded = VaultIndexCodec.decode(forward) as VaultIndexCodec.DecodeResult.Decoded
        assertEquals(listOf(second.id, first.id), decoded.items.map { it.id })
    }

    @Test fun emptyAndPopulatedIndexesAreDistinctValidStates() {
        val empty = VaultIndexCodec.decode(VaultIndexCodec.encode(vaultId, 0, emptyList()))
        val populated = VaultIndexCodec.decode(VaultIndexCodec.encode(vaultId, 1, listOf(item("00000000000000000000000000000001", "photo.jpg"))))
        assertTrue((empty as VaultIndexCodec.DecodeResult.Decoded).items.isEmpty())
        assertEquals(1, (populated as VaultIndexCodec.DecodeResult.Decoded).items.size)
    }

    @Test fun unsupportedVersionIsNotCorruptionOrEmpty() {
        val bytes = VaultIndexCodec.encode(vaultId, 0, emptyList())
        bytes[4] = 77
        assertEquals(VaultIndexCodec.DecodeResult.Unsupported(77), VaultIndexCodec.decode(bytes))
    }

    @Test fun tamperingAndTrailingBytesAreRejected() {
        val encoded = VaultIndexCodec.encode(vaultId, 0, emptyList())
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 0x01).toByte()
        assertEquals(VaultIndexCodec.DecodeResult.Invalid, VaultIndexCodec.decode(encoded))
        val valid = VaultIndexCodec.encode(vaultId, 0, emptyList())
        assertEquals(VaultIndexCodec.DecodeResult.Invalid, VaultIndexCodec.decode(valid + byteArrayOf(0)))
    }

    @Test fun trashLifecycleAndDigestRoundTripWithoutChangingContentIdentity() {
        val digest = ByteArray(VaultItem.SHA_256_BYTES) { it.toByte() }
        val active = item("00000000000000000000000000000001", "kept.pdf").withLifecycle(VaultItemLifecycle.ACTIVE)
        val trashed = VaultItem(
            active.id, active.originalFilename, active.originalMimeType, active.originalSizeBytes,
            active.importedAtEpochMillis, active.objectFormatVersion, active.objectSizeBytes,
            active.encryptedItemKey, VaultItemLifecycle.TRASHED, 1_700_000_000_123L, digest,
        )
        val decoded = (VaultIndexCodec.decode(VaultIndexCodec.encode(vaultId, 8, listOf(trashed)))
            as VaultIndexCodec.DecodeResult.Decoded).items.single()
        assertEquals(active.id, decoded.id)
        assertEquals(active.originalFilename, decoded.originalFilename)
        assertEquals(active.originalMimeType, decoded.originalMimeType)
        assertEquals(active.originalSizeBytes, decoded.originalSizeBytes)
        assertEquals(active.importedAtEpochMillis, decoded.importedAtEpochMillis)
        assertEquals(active.objectFormatVersion, decoded.objectFormatVersion)
        assertEquals(active.objectSizeBytes, decoded.objectSizeBytes)
        assertEquals(VaultItemLifecycle.TRASHED, decoded.lifecycle)
        assertEquals(1_700_000_000_123L, decoded.trashedAtEpochMillis)
        assertTrue(decoded.contentDigestSha256!!.contentEquals(digest))
        val restored = decoded.withLifecycle(VaultItemLifecycle.ACTIVE)
        assertEquals(decoded.id, restored.id)
        assertEquals(null, restored.trashedAtEpochMillis)
        assertEquals(VaultItemLifecycle.ACTIVE, restored.lifecycle)
        assertTrue(restored.contentDigestSha256!!.contentEquals(digest))
        digest.fill(0)
    }

    @Test fun versionOneIndexRemainsReadableAndUpgradesAsActiveWithoutInventingDigest() {
        val item = item("00000000000000000000000000000001", "legacy.pdf")
        val legacy = encodeLegacyVersionOne(item, generation = 2)
        val decoded = VaultIndexCodec.decode(legacy) as VaultIndexCodec.DecodeResult.Decoded
        assertEquals(1, legacy[4].toInt())
        assertEquals(VaultItemLifecycle.ACTIVE, decoded.items.single().lifecycle)
        assertEquals(null, decoded.items.single().trashedAtEpochMillis)
        assertEquals(null, decoded.items.single().contentDigestSha256)
        assertEquals(VaultIndexCodec.VERSION, VaultIndexCodec.encode(vaultId, 3, decoded.items)[4].toInt())
        legacy.fill(0)
    }

    @Test fun invalidTrashStateTimestampAndDigestMarkerAreRejected() {
        val encoded = VaultIndexCodec.encode(vaultId, 1, listOf(item("00000000000000000000000000000001", "a.txt")))
        val recordLengthOffset = 4 + 1 + 16 + 8 + 4
        val recordLength = java.nio.ByteBuffer.wrap(encoded, recordLengthOffset, 4).int
        val recordStart = recordLengthOffset + 4
        val stateOffset = recordStart + recordLength - 10
        encoded[stateOffset] = 7
        assertEquals(VaultIndexCodec.DecodeResult.Invalid, VaultIndexCodec.decode(encoded))
        val activeTimestamp = VaultIndexCodec.encode(vaultId, 1, listOf(item("00000000000000000000000000000001", "a.txt")))
        activeTimestamp[stateOffset + 1] = 0 // corrupt the active timestamp sentinel
        assertEquals(VaultIndexCodec.DecodeResult.Invalid, VaultIndexCodec.decode(activeTimestamp))
        val invalidDigestMarker = VaultIndexCodec.encode(vaultId, 1, listOf(item("00000000000000000000000000000001", "a.txt")))
        invalidDigestMarker[stateOffset + 9] = 2
        assertEquals(VaultIndexCodec.DecodeResult.Invalid, VaultIndexCodec.decode(invalidDigestMarker))
    }

    @Test fun indexAndMetadataBoundsRejectOversizeRowsDigestsAndPayloads() {
        val valid = item("00000000000000000000000000000001", "bounded.pdf")
        assertTrue(runCatching {
            VaultIndexCodec.encode(vaultId, 1, List(VaultIndexCodec.MAX_ITEMS + 1) { valid })
        }.isFailure)
        assertEquals(VaultIndexCodec.DecodeResult.Invalid,
            VaultIndexCodec.decode(ByteArray(VaultIndexCodec.MAX_INDEX_BYTES + 1)))
        assertTrue(runCatching {
            VaultItem(valid.id, valid.originalFilename, valid.originalMimeType, valid.originalSizeBytes,
                valid.importedAtEpochMillis, valid.objectFormatVersion, valid.objectSizeBytes,
                valid.encryptedItemKey, contentDigestSha256 = ByteArray(VaultItem.SHA_256_BYTES - 1))
        }.isFailure)
        assertTrue(runCatching { valid.withLifecycle(VaultItemLifecycle.TRASHED, -1) }.isFailure)
        assertTrue(runCatching { valid.withLifecycle(VaultItemLifecycle.ACTIVE, 1) }.isFailure)
    }

    @Test fun filenameValidationRejectsPathsControlsAndTraversal() {
        listOf("", ".", "..", "../report.txt", "a/b", "a\\b", "line\nfeed").forEach {
            assertFalse("accepted $it", VaultItemValidation.validateFilename(it))
        }
        assertTrue(VaultItemValidation.validateFilename("quarterly report.pdf"))
        assertFalse(VaultItemValidation.validateMimeType("../../secret"))
    }

    @Test fun vaultItemIdIsFixedWidthAndLowercaseOnly() {
        assertEquals("00000000000000000000000000000001", VaultItemId.fromBytes(ByteArray(15) + byteArrayOf(1)).value)
        assertTrue(runCatching { VaultItemId("../x") }.isFailure)
    }

    @Test fun envelopeRequiresMatchingLengthAndGeneration() {
        val encoded = VaultIndexEnvelopeCodec.encode(9, byteArrayOf(1, 2, 3))
        val decoded = VaultIndexEnvelopeCodec.decode(encoded) as VaultIndexEnvelopeCodec.DecodeResult.Valid
        assertEquals(9L, decoded.value.generation)
        assertTrue(decoded.value.encryptedRecord.contentEquals(byteArrayOf(1, 2, 3)))
        encoded[encoded.lastIndex] = 4
        val changed = VaultIndexEnvelopeCodec.decode(encoded) as VaultIndexEnvelopeCodec.DecodeResult.Valid
        assertFalse(changed.value.encryptedRecord.contentEquals(byteArrayOf(1, 2, 3)))
    }

    private fun encodeLegacyVersionOne(item: VaultItem, generation: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(byteArrayOf(0x4e, 0x56, 0x49, 0x44))
            out.writeByte(VaultIndexCodec.LEGACY_VERSION)
            out.write(vaultId.toBytes())
            out.writeLong(generation)
            out.writeInt(1)
            val recordBytes = ByteArrayOutputStream()
            DataOutputStream(recordBytes).use { row ->
                row.write(item.id.toBytes())
                val name = item.originalFilename.toByteArray(Charsets.UTF_8)
                row.writeShort(name.size); row.write(name)
                val mime = item.originalMimeType!!.toByteArray(Charsets.UTF_8)
                row.writeShort(mime.size); row.write(mime)
                row.writeLong(item.originalSizeBytes); row.writeLong(item.importedAtEpochMillis)
                row.writeByte(item.objectFormatVersion); row.writeLong(item.objectSizeBytes)
                val key = item.encryptedItemKey
                try { row.writeShort(key.size); row.write(key) } finally { key.fill(0) }
            }
            val record = recordBytes.toByteArray()
            out.writeInt(record.size); out.write(record)
        }
        return bytes.toByteArray()
    }

    private fun item(id: String, name: String) = VaultItem(
        id = VaultItemId(id),
        originalFilename = name,
        originalMimeType = "application/pdf",
        originalSizeBytes = 128,
        importedAtEpochMillis = 1000,
        objectFormatVersion = VaultContentObjectCodec.VERSION,
        objectSizeBytes = VaultContentObjectCodec.HEADER_BYTES + 128 + 16L,
        encryptedItemKey = byteArrayOf(1, 2, 3),
    )
}
