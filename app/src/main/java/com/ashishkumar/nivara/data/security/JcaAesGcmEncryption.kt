package com.ashishkumar.nivara.data.security

import com.ashishkumar.nivara.domain.security.Aes256Key
import com.ashishkumar.nivara.domain.security.AuthenticatedEncryption
import com.ashishkumar.nivara.domain.security.CryptoContext
import com.ashishkumar.nivara.domain.security.EncryptedEnvelope
import com.ashishkumar.nivara.domain.security.SecurityFailure
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM implementation. JCA appends the 128-bit authentication tag to the ciphertext. */
class JcaAesGcmEncryption(
    private val random: com.ashishkumar.nivara.domain.security.SecureRandomSource,
) : AuthenticatedEncryption {
    override fun encrypt(
        plaintext: ByteArray,
        key: Aes256Key,
        context: CryptoContext,
    ): EncryptedEnvelope {
        var nonceToClear: ByteArray? = null
        var encryptedToClear: ByteArray? = null
        var aadToClear: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val nonce = if (key.usesProviderNonce()) {
                // AndroidKeyStore requires provider-generated IVs when randomized encryption is enabled.
                cipher.init(Cipher.ENCRYPT_MODE, key.secretKey())
                cipher.iv?.copyOf() ?: throw SecurityFailure.CryptoOperationFailed()
            } else {
                random.generateGcmNonce().also { generated ->
                    if (generated.size != EncryptedEnvelope.NONCE_BYTES) {
                        generated.fill(0)
                        throw SecurityFailure.InvalidParameters()
                    }
                    cipher.init(
                        Cipher.ENCRYPT_MODE,
                        key.secretKey(),
                        GCMParameterSpec(TAG_BITS, generated),
                    )
                }
            }
            nonceToClear = nonce
            if (nonce.size != EncryptedEnvelope.NONCE_BYTES) throw SecurityFailure.CryptoOperationFailed()
            val aad = associatedData(context)
            aadToClear = aad
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plaintext)
            encryptedToClear = encrypted
            if (encrypted.size < EncryptedEnvelope.TAG_BYTES) throw SecurityFailure.CryptoOperationFailed()
            return EncryptedEnvelope(
                version = EncryptedEnvelope.CURRENT_VERSION,
                algorithmId = EncryptedEnvelope.AES_256_GCM_ID,
                nonce = nonce,
                ciphertextAndTag = encrypted,
            )
        } catch (failure: SecurityFailure) {
            throw failure
        } catch (failure: InvalidKeyException) {
            throw SecurityFailure.InvalidKey()
        } catch (failure: GeneralSecurityException) {
            throw SecurityFailure.CryptoOperationFailed()
        } finally {
            nonceToClear?.fill(0)
            encryptedToClear?.fill(0)
            aadToClear?.fill(0)
        }
    }

    override fun decrypt(
        envelope: EncryptedEnvelope,
        key: Aes256Key,
        context: CryptoContext,
    ): ByteArray {
        if (envelope.version != EncryptedEnvelope.CURRENT_VERSION) {
            throw SecurityFailure.UnsupportedEnvelopeVersion()
        }
        if (envelope.algorithmId != EncryptedEnvelope.AES_256_GCM_ID) {
            throw SecurityFailure.UnsupportedAlgorithm()
        }
        val nonce = envelope.nonceForCrypto()
        val encrypted = envelope.ciphertextForCrypto()
        if (nonce.size != EncryptedEnvelope.NONCE_BYTES || encrypted.size < EncryptedEnvelope.TAG_BYTES) {
            nonce.fill(0)
            encrypted.fill(0)
            throw SecurityFailure.InvalidEnvelope()
        }
        var aadToClear: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key.secretKey(), GCMParameterSpec(TAG_BITS, nonce))
            val aad = associatedData(context)
            aadToClear = aad
            cipher.updateAAD(aad)
            return cipher.doFinal(encrypted)
        } catch (failure: AEADBadTagException) {
            throw SecurityFailure.AuthenticationFailed()
        } catch (failure: BadPaddingException) {
            throw SecurityFailure.AuthenticationFailed()
        } catch (failure: SecurityFailure) {
            throw failure
        } catch (failure: InvalidKeyException) {
            throw SecurityFailure.InvalidKey()
        } catch (failure: GeneralSecurityException) {
            throw SecurityFailure.CryptoOperationFailed()
        } finally {
            nonce.fill(0)
            encrypted.fill(0)
            aadToClear?.fill(0)
        }
    }

    private fun associatedData(context: CryptoContext): ByteArray {
        val purpose = context.purpose.toByteArray(StandardCharsets.UTF_8)
        val binding = context.binding
        return try {
            ByteBuffer.allocate(AAD_DOMAIN.size + 1 + 1 + 4 + purpose.size + 4 + binding.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(AAD_DOMAIN)
                .put(EncryptedEnvelope.CURRENT_VERSION.toByte())
                .put(EncryptedEnvelope.AES_256_GCM_ID.toByte())
                .putInt(purpose.size)
                .put(purpose)
                .putInt(binding.size)
                .put(binding)
                .array()
        } finally {
            purpose.fill(0)
            binding.fill(0)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = EncryptedEnvelope.TAG_BYTES * 8
        val AAD_DOMAIN = "Nivara:EncryptedEnvelope".toByteArray(StandardCharsets.US_ASCII)
    }
}
