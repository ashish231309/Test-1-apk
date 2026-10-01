package com.ashishkumar.nivara.data.security

import com.ashishkumar.nivara.domain.security.Aes256Key
import com.ashishkumar.nivara.domain.security.CryptoContext
import com.ashishkumar.nivara.domain.security.KeyProtection
import com.ashishkumar.nivara.domain.security.SecurityFailure
import com.ashishkumar.nivara.domain.security.WrappedKeyEnvelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AesGcmKeyWrappingServiceTest {
    private val random = JcaSecureRandomSource()
    private val encryption = JcaAesGcmEncryption(random)
    private val wrapping = AesGcmKeyWrappingService(encryption)
    private val context = CryptoContext("vault-key", byteArrayOf(2, 7))

    @Test
    fun wrapsAndUnwrapsHighEntropyContentKeyWithVersionedEnvelope() {
        val wrappingKey = newKey()
        val contentKey = random.generateAes256KeyBytes()
        try {
            val wrapped = wrapping.wrap(contentKey, wrappingKey, KeyProtection.RECOVERY, context)
            val decoded = WrappedKeyEnvelope.decode(wrapped.encode())
            val unwrapped = wrapping.unwrap(decoded, wrappingKey, KeyProtection.RECOVERY, context)
            try {
                assertArrayEquals(contentKey, unwrapped)
            } finally {
                unwrapped.fill(0)
            }
        } finally {
            contentKey.fill(0)
        }
    }

    @Test
    fun outerVersionAndProtectionTamperingAreRejected() {
        val wrappingKey = newKey()
        val contentKey = random.generateAes256KeyBytes()
        try {
            val encoded = wrapping.wrap(contentKey, wrappingKey, KeyProtection.CREDENTIAL_DERIVED, context).encode()
            val unsupportedVersion = encoded.copyOf().also { it[4] = 2 }
            assertThrows(SecurityFailure.UnsupportedEnvelopeVersion::class.java) {
                WrappedKeyEnvelope.decode(unsupportedVersion)
            }
            val changedProtection = encoded.copyOf().also { it[5] = KeyProtection.RECOVERY.wireId.toByte() }
            val tampered = WrappedKeyEnvelope.decode(changedProtection)
            assertThrows(SecurityFailure.AuthenticationFailed::class.java) {
                wrapping.unwrap(tampered, wrappingKey, KeyProtection.RECOVERY, context)
            }
        } finally {
            contentKey.fill(0)
        }
    }

    @Test
    fun wrongProtectionOrContextCannotUnwrap() {
        val wrappingKey = newKey()
        val contentKey = random.generateAes256KeyBytes()
        try {
            val wrapped = wrapping.wrap(contentKey, wrappingKey, KeyProtection.CREDENTIAL_DERIVED, context)
            assertThrows(SecurityFailure.InvalidEnvelope::class.java) {
                wrapping.unwrap(wrapped, wrappingKey, KeyProtection.RECOVERY, context)
            }
            assertThrows(SecurityFailure.AuthenticationFailed::class.java) {
                wrapping.unwrap(
                    wrapped,
                    wrappingKey,
                    KeyProtection.CREDENTIAL_DERIVED,
                    CryptoContext("other-purpose", byteArrayOf(2, 7)),
                )
            }
        } finally {
            contentKey.fill(0)
        }
    }

    private fun newKey(): Aes256Key {
        val bytes = random.generateAes256KeyBytes()
        return try { Aes256Key.fromBytes(bytes) } finally { bytes.fill(0) }
    }
}
