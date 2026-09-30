package com.ashishkumar.nivara.data.security

import com.ashishkumar.nivara.domain.security.Aes256Key
import com.ashishkumar.nivara.domain.security.CryptoContext
import com.ashishkumar.nivara.domain.security.EncryptedEnvelope
import com.ashishkumar.nivara.domain.security.SecurityFailure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class JcaAesGcmEncryptionTest {
    private val random = JcaSecureRandomSource()
    private val encryption = JcaAesGcmEncryption(random)
    private val context = CryptoContext("unit-test/payload", byteArrayOf(3, 1, 4))

    @Test
    fun encryptDecryptRoundTripAndUsesCombinedTagRepresentation() {
        val key = newKey()
        val plaintext = "Nivara test payload".toByteArray()
        val envelope = encryption.encrypt(plaintext, key, context)

        val decrypted = encryption.decrypt(envelope, key, context)
        try {
            assertArrayEquals(plaintext, decrypted)
        } finally {
            decrypted.fill(0)
            plaintext.fill(0)
        }
        assertEquals(EncryptedEnvelope.CURRENT_VERSION, envelope.version)
        assertEquals(EncryptedEnvelope.AES_256_GCM_ID, envelope.algorithmId)
        assertEquals(EncryptedEnvelope.NONCE_BYTES, envelope.nonce.size)
        assertEquals(plaintext.size + EncryptedEnvelope.TAG_BYTES, envelope.ciphertextAndTag.size)
        assertEquals(23 + plaintext.size + EncryptedEnvelope.TAG_BYTES, envelope.encode().size)
    }

    @Test
    fun encryptingSamePlaintextTwiceUsesDifferentNonceAndEnvelope() {
        val key = newKey()
        val plaintext = byteArrayOf(9, 8, 7, 6)
        val first = encryption.encrypt(plaintext, key, context)
        val second = encryption.encrypt(plaintext, key, context)

        assertFalse(first.nonce.contentEquals(second.nonce))
        assertFalse(first.encode().contentEquals(second.encode()))
    }

    @Test
    fun wrongKeyIsRejected() {
        val key = newKey()
        val envelope = encryption.encrypt(byteArrayOf(1, 2, 3), key, context)
        assertThrows(SecurityFailure.AuthenticationFailed::class.java) {
            encryption.decrypt(envelope, newKey(), context)
        }
    }

    @Test
    fun modifiedCiphertextAndTagAreRejected() {
        val key = newKey()
        val envelope = encryption.encrypt(byteArrayOf(10, 20, 30), key, context)
        val encoded = envelope.encode()
        encoded[23] = (encoded[23].toInt() xor 1).toByte()
        val altered = EncryptedEnvelope.decode(encoded)

        assertThrows(SecurityFailure.AuthenticationFailed::class.java) {
            encryption.decrypt(altered, key, context)
        }
    }

    @Test
    fun modifiedAuthenticationTagIsRejected() {
        val key = newKey()
        val encoded = encryption.encrypt(byteArrayOf(5, 4, 3), key, context).encode()
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()

        assertThrows(SecurityFailure.AuthenticationFailed::class.java) {
            encryption.decrypt(EncryptedEnvelope.decode(encoded), key, context)
        }
    }

    @Test
    fun modifiedNonceIsRejected() {
        val key = newKey()
        val encoded = encryption.encrypt(byteArrayOf(1, 2, 3), key, context).encode()
        encoded[7] = (encoded[7].toInt() xor 1).toByte()

        assertThrows(SecurityFailure.AuthenticationFailed::class.java) {
            encryption.decrypt(EncryptedEnvelope.decode(encoded), key, context)
        }
    }

    @Test
    fun changedPurposeOrBindingIsRejected() {
        val key = newKey()
        val envelope = encryption.encrypt(byteArrayOf(1), key, context)
        assertThrows(SecurityFailure.AuthenticationFailed::class.java) {
            encryption.decrypt(envelope, key, CryptoContext("unit-test/other", byteArrayOf(3, 1, 4)))
        }
        assertThrows(SecurityFailure.AuthenticationFailed::class.java) {
            encryption.decrypt(envelope, key, CryptoContext("unit-test/payload", byteArrayOf(3, 1, 5)))
        }
    }

    @Test
    fun malformedTruncatedUnsupportedVersionAndAlgorithmAreRejected() {
        val key = newKey()
        val encoded = encryption.encrypt(byteArrayOf(1, 2), key, context).encode()
        assertThrows(SecurityFailure.InvalidEnvelope::class.java) {
            EncryptedEnvelope.decode(encoded.copyOf(encoded.size - 1))
        }
        assertThrows(SecurityFailure.InvalidEnvelope::class.java) {
            EncryptedEnvelope.decode(byteArrayOf(1, 2, 3))
        }

        val unsupportedVersion = encoded.copyOf().also { it[4] = 2 }
        assertThrows(SecurityFailure.UnsupportedEnvelopeVersion::class.java) {
            EncryptedEnvelope.decode(unsupportedVersion)
        }
        val unsupportedAlgorithm = encoded.copyOf().also { it[5] = 2 }
        assertThrows(SecurityFailure.UnsupportedAlgorithm::class.java) {
            EncryptedEnvelope.decode(unsupportedAlgorithm)
        }
    }

    @Test
    fun nonceAndKeyLengthsAreEnforced() {
        val nonce = random.generateGcmNonce()
        try {
            assertEquals(12, nonce.size)
        } finally {
            nonce.fill(0)
        }
        assertThrows(SecurityFailure.InvalidKey::class.java) { Aes256Key.fromBytes(ByteArray(16)) }
    }

    private fun newKey(): Aes256Key {
        val bytes = random.generateAes256KeyBytes()
        return try {
            Aes256Key.fromBytes(bytes)
        } finally {
            bytes.fill(0)
        }
    }

}
