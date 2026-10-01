package com.ashishkumar.nivara.data.security

import com.ashishkumar.nivara.domain.security.Aes256Key
import com.ashishkumar.nivara.domain.security.AuthenticatedEncryption
import com.ashishkumar.nivara.domain.security.CryptoContext
import com.ashishkumar.nivara.domain.security.EncryptedEnvelope
import com.ashishkumar.nivara.domain.security.SecurityFailure
import com.ashishkumar.nivara.domain.security.StreamAuthorizationExpired
import com.ashishkumar.nivara.domain.security.StreamCipherSummary
import com.ashishkumar.nivara.domain.security.StreamingAuthenticatedEncryption
import java.io.InputStream
import java.io.OutputStream
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
 ) : StreamingAuthenticatedEncryption {
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

    override suspend fun encryptStream(
        input: InputStream,
        output: OutputStream,
        key: Aes256Key,
        nonce: ByteArray,
        context: CryptoContext,
        authorizationCheckpoint: suspend () -> Boolean,
        onProgress: (Long) -> Unit,
    ): StreamCipherSummary = processStream(
        encrypt = true,
        input = input,
        output = output,
        key = key,
        nonce = nonce,
        context = context,
        authorizationCheckpoint = authorizationCheckpoint,
        onProgress = onProgress,
    )

    override suspend fun decryptStream(
        input: InputStream,
        output: OutputStream,
        key: Aes256Key,
        nonce: ByteArray,
        context: CryptoContext,
        authorizationCheckpoint: suspend () -> Boolean,
    ): StreamCipherSummary = processStream(
        encrypt = false,
        input = input,
        output = output,
        key = key,
        nonce = nonce,
        context = context,
        authorizationCheckpoint = authorizationCheckpoint,
        onProgress = {},
    )

    private suspend fun processStream(
        encrypt: Boolean,
        input: InputStream,
        output: OutputStream,
        key: Aes256Key,
        nonce: ByteArray,
        context: CryptoContext,
        authorizationCheckpoint: suspend () -> Boolean,
        onProgress: (Long) -> Unit,
    ): StreamCipherSummary {
        if (nonce.size != EncryptedEnvelope.NONCE_BYTES || key.usesProviderNonce()) {
            throw SecurityFailure.InvalidParameters()
        }
        val iv = nonce.copyOf()
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var aad: ByteArray? = null
        var totalPlain = 0L
        var totalCipher = 0L
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                key.secretKey(),
                GCMParameterSpec(TAG_BITS, iv),
            )
            aad = associatedData(context)
            cipher.updateAAD(aad)
            while (true) {
                if (!authorizationCheckpoint()) throw StreamAuthorizationExpired()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (!encrypt) totalCipher = Math.addExact(totalCipher, count.toLong())
                val transformed = cipher.update(buffer, 0, count)
                if (transformed != null && transformed.isNotEmpty()) {
                    try {
                        output.write(transformed)
                        if (encrypt) totalCipher = Math.addExact(totalCipher, transformed.size.toLong())
                        else totalPlain = Math.addExact(totalPlain, transformed.size.toLong())
                    } finally { transformed.fill(0) }
                }
                if (encrypt) {
                    totalPlain = Math.addExact(totalPlain, count.toLong())
                    onProgress(totalPlain)
                }
            }
            if (!authorizationCheckpoint()) throw StreamAuthorizationExpired()
            val finalBytes = cipher.doFinal()
            if (finalBytes.isNotEmpty()) {
                try {
                    output.write(finalBytes)
                    if (encrypt) totalCipher = Math.addExact(totalCipher, finalBytes.size.toLong())
                    else totalPlain = Math.addExact(totalPlain, finalBytes.size.toLong())
                } finally { finalBytes.fill(0) }
            }
            output.flush()
            if (!encrypt && totalCipher < EncryptedEnvelope.TAG_BYTES) {
                throw SecurityFailure.AuthenticationFailed()
            }
            return StreamCipherSummary(totalPlain, totalCipher)
        } catch (failure: StreamAuthorizationExpired) {
            throw failure
        } catch (failure: AEADBadTagException) {
            throw SecurityFailure.AuthenticationFailed()
        } catch (failure: BadPaddingException) {
            throw SecurityFailure.AuthenticationFailed()
        } catch (failure: InvalidKeyException) {
            throw SecurityFailure.InvalidKey()
        } catch (failure: GeneralSecurityException) {
            throw SecurityFailure.CryptoOperationFailed()
        } finally {
            iv.fill(0)
            buffer.fill(0)
            aad?.fill(0)
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
        const val STREAM_BUFFER_BYTES = 64 * 1024
        val AAD_DOMAIN = "Nivara:EncryptedEnvelope".toByteArray(StandardCharsets.US_ASCII)
    }
}
