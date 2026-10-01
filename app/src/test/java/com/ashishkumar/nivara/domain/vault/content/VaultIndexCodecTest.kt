package com.ashishkumar.nivara.domain.vault.content

import com.ashishkumar.nivara.domain.vault.VaultId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
