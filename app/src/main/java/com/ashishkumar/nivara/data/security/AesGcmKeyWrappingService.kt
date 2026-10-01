package com.ashishkumar.nivara.data.security

import com.ashishkumar.nivara.domain.security.Aes256Key
import com.ashishkumar.nivara.domain.security.AuthenticatedEncryption
import com.ashishkumar.nivara.domain.security.CryptoContext
import com.ashishkumar.nivara.domain.security.KeyProtection
import com.ashishkumar.nivara.domain.security.KeyWrappingService
import com.ashishkumar.nivara.domain.security.SecurityFailure
import com.ashishkumar.nivara.domain.security.WrappedKeyEnvelope
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Wraps random 256-bit data keys without coupling the wrapper to credential, recovery, or device-key sources. */
class AesGcmKeyWrappingService(
    private val encryption: AuthenticatedEncryption,
) : KeyWrappingService {
    override fun wrap(
        contentKey: ByteArray,
        wrappingKey: Aes256Key,
        protection: KeyProtection,
        context: CryptoContext,
    ): WrappedKeyEnvelope {
        if (contentKey.size != Aes256Key.KEY_BYTES) throw SecurityFailure.InvalidKey()
        val encrypted = encryption.encrypt(contentKey, wrappingKey, wrappingContext(protection, context))
        return WrappedKeyEnvelope(protection, encrypted)
    }

    override fun unwrap(
        wrappedKey: WrappedKeyEnvelope,
        wrappingKey: Aes256Key,
        expectedProtection: KeyProtection,
        context: CryptoContext,
    ): ByteArray {
        if (wrappedKey.protection != expectedProtection) throw SecurityFailure.InvalidEnvelope()
        val contentKey = encryption.decrypt(
            wrappedKey.encryptedKey,
            wrappingKey,
            wrappingContext(expectedProtection, context),
        )
        if (contentKey.size != Aes256Key.KEY_BYTES) {
            contentKey.fill(0)
            throw SecurityFailure.InvalidEnvelope()
        }
        return contentKey
    }

    private fun wrappingContext(protection: KeyProtection, context: CryptoContext): CryptoContext {
        val purpose = context.purpose.toByteArray(StandardCharsets.UTF_8)
        val binding = context.binding
        val combined = ByteBuffer.allocate(1 + 4 + purpose.size + 4 + binding.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(protection.wireId.toByte())
            .putInt(purpose.size)
            .put(purpose)
            .putInt(binding.size)
            .put(binding)
            .array()
        purpose.fill(0)
        binding.fill(0)
        return try {
            CryptoContext("Nivara:KeyWrapping:v1", combined)
        } finally {
            combined.fill(0)
        }
    }
}
