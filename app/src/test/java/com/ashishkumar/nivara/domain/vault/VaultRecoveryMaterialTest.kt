package com.ashishkumar.nivara.domain.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultRecoveryMaterialTest {
    private val vaultId = VaultId("00112233445566778899aabbccddeeff")

    @Test
    fun recoveryCodeRoundTripsHighEntropyMaterialAndNormalizesCaseSeparators() {
        val key = ByteArray(VaultRecoveryCodeCodec.KEY_BYTES) { it.toByte() }
        try {
            val code = VaultRecoveryCodeCodec.encode(key)
            assertTrue(code.startsWith("NVR1-"))
            assertTrue(code.length <= VaultRecoveryCodeCodec.MAX_INPUT_CHARS)
            val decoded = VaultRecoveryCodeCodec.decode(code.lowercase()) as VaultRecoveryCodeCodec.Decode.Valid
            val decodedKey = decoded.keyBytes()
            try { assertArrayEquals(key, decodedKey) } finally { decodedKey.fill(0); decoded.clear() }

            val compact = code.replace("-", "")
            val compactDecoded = VaultRecoveryCodeCodec.decode(compact) as VaultRecoveryCodeCodec.Decode.Valid
            val compactKey = compactDecoded.keyBytes()
            try { assertArrayEquals(key, compactKey) } finally { compactKey.fill(0); compactDecoded.clear() }
        } finally { key.fill(0) }
    }

    @Test
    fun recoveryCodeRejectsTypographicalCorruptionInvalidPaddingUnicodeAndOversizedInput() {
        val key = ByteArray(VaultRecoveryCodeCodec.KEY_BYTES) { (it * 7).toByte() }
        try {
            val code = VaultRecoveryCodeCodec.encode(key)
            val changed = code.toCharArray().also { chars ->
                val index = chars.indexOfFirst { it in 'A'..'Z' || it in '2'..'7' }
                chars[index] = if (chars[index] == 'A') 'B' else 'A'
            }.concatToString()
            assertEquals(VaultRecoveryCodeCodec.Decode.Invalid, VaultRecoveryCodeCodec.decode(changed))
            assertEquals(VaultRecoveryCodeCodec.Decode.Invalid, VaultRecoveryCodeCodec.decode("NVR1-" + "A".repeat(56)))
            assertEquals(VaultRecoveryCodeCodec.Decode.Invalid, VaultRecoveryCodeCodec.decode(code + "é"))
            assertEquals(
                VaultRecoveryCodeCodec.Decode.Invalid,
                VaultRecoveryCodeCodec.decode("A".repeat(VaultRecoveryCodeCodec.MAX_INPUT_CHARS + 1)),
            )
        } finally { key.fill(0) }
    }

    @Test
    fun recoveryRecordUsesIndependentStrictVersionedFraming() {
        val envelope = byteArrayOf(4, 5, 6, 7, 8)
        val bytes = VaultRecoveryRecordCodec.encode(VaultRecoveryRecord(vaultId, envelope))
        val decoded = VaultRecoveryRecordCodec.decode(bytes) as VaultRecoveryRecordDecode.Supported
        assertEquals(vaultId, decoded.record.vaultId)
        assertArrayEquals(envelope, decoded.record.wrappedContentKey)
        assertEquals(VaultRecoveryRecordDecode.Invalid, VaultRecoveryRecordCodec.decode(bytes.copyOf(bytes.size - 1)))
        assertEquals(VaultRecoveryRecordDecode.Invalid, VaultRecoveryRecordCodec.decode(bytes + byteArrayOf(0)))
        assertEquals(
            VaultRecoveryRecordDecode.Invalid,
            VaultRecoveryRecordCodec.decode(bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }),
        )
        assertEquals(
            VaultRecoveryRecordDecode.UnsupportedVersion(2),
            VaultRecoveryRecordCodec.decode(bytes.copyOf().also { it[4] = 2 }),
        )
    }

    @Test
    fun recoveryModelsRedactEnvelopeAndKeyMaterialInStringRepresentations() {
        val key = ByteArray(32) { 0x5a }
        try {
            val valid = VaultRecoveryCodeCodec.decode(VaultRecoveryCodeCodec.encode(key)) as VaultRecoveryCodeCodec.Decode.Valid
            assertFalse(valid.toString().contains("5a"))
            valid.clear()
            assertFalse(VaultRecoveryRecord(vaultId, byteArrayOf(1, 2, 3)).toString().contains("1, 2, 3"))
        } finally { key.fill(0) }
    }
}
