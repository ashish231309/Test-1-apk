package com.ashishkumar.nivara.domain.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultMetadataCodecTest {
    private val vaultId = VaultId("00112233445566778899aabbccddeeff")

    @Test
    fun metadataRoundTripsStrictVersionedOpaqueEnvelopes() {
        val wrapped = byteArrayOf(1, 2, 3, 4)
        val encryptedHeader = byteArrayOf(5, 6, 7)
        val encoded = VaultMetadataCodec.encode(VaultMetadataEnvelope(vaultId, wrapped, encryptedHeader))
        val decoded = VaultMetadataCodec.decode(encoded) as VaultMetadataDecode.Supported

        assertEquals(vaultId, decoded.metadata.vaultId)
        assertArrayEquals(wrapped, decoded.metadata.wrappedContentKey)
        assertArrayEquals(encryptedHeader, decoded.metadata.encryptedHeader)
    }

    @Test
    fun malformedMagicLengthsTruncationAndTrailingBytesFailClosed() {
        val valid = VaultMetadataCodec.encode(VaultMetadataEnvelope(vaultId, byteArrayOf(1), byteArrayOf(2)))
        assertEquals(VaultMetadataDecode.Invalid, VaultMetadataCodec.decode(byteArrayOf(1, 2, 3)))
        assertEquals(VaultMetadataDecode.Invalid,
            VaultMetadataCodec.decode(valid.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
        assertEquals(VaultMetadataDecode.Invalid, VaultMetadataCodec.decode(valid.copyOf(valid.size - 1)))
        assertEquals(VaultMetadataDecode.Invalid, VaultMetadataCodec.decode(valid + byteArrayOf(0)))
    }

    @Test
    fun unknownMetadataVersionIsDistinctFromCorruption() {
        val valid = VaultMetadataCodec.encode(VaultMetadataEnvelope(vaultId, byteArrayOf(1), byteArrayOf(2)))
        valid[11] = 2
        val decoded = VaultMetadataCodec.decode(valid)
        assertTrue(decoded is VaultMetadataDecode.UnsupportedVersion)
        assertEquals(2, (decoded as VaultMetadataDecode.UnsupportedVersion).version)
    }

    @Test
    fun badNestedLengthsAreRejected() {
        val valid = VaultMetadataCodec.encode(VaultMetadataEnvelope(vaultId, byteArrayOf(1, 2), byteArrayOf(3)))
        // wrapped-key length begins after magic, format version, and the 16-byte vault id.
        valid[28] = 0x7f
        valid[29] = 0x7f
        valid[30] = 0x7f
        valid[31] = 0x7f
        assertEquals(VaultMetadataDecode.Invalid, VaultMetadataCodec.decode(valid))
    }

    @Test
    fun encryptedHeaderBindsSupportedFormatAndVaultIdentity() {
        val encoded = VaultHeaderCodec.encode(vaultId)
        assertEquals(VaultHeaderDecode.Supported(vaultId), VaultHeaderCodec.decode(encoded))
        val unsupported = encoded.copyOf().also { it[11] = 2 }
        assertEquals(VaultHeaderDecode.UnsupportedVersion(2), VaultHeaderCodec.decode(unsupported))
        assertEquals(VaultHeaderDecode.Invalid, VaultHeaderCodec.decode(encoded.copyOf(encoded.size - 1)))
    }
}
