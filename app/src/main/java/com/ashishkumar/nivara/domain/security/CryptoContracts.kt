package com.ashishkumar.nivara.domain.security

import javax.crypto.SecretKey

/** Source of cryptographically secure bytes; implementations must never use general-purpose Random. */
interface SecureRandomSource {
    fun generateBytes(size: Int): ByteArray

    fun generateAes256KeyBytes(): ByteArray = generateBytes(Aes256Key.KEY_BYTES)
    fun generateAes256Key(): Aes256Key {
        val material = generateAes256KeyBytes()
        return try {
            Aes256Key.fromBytes(material)
        } finally {
            material.fill(0)
        }
    }
    fun generateGcmNonce(): ByteArray = generateBytes(EncryptedEnvelope.NONCE_BYTES)
    fun generateSalt(size: Int = KdfParameters.DEFAULT.saltBytes): ByteArray = generateBytes(size)
    fun generateRecoveryKeyBytes(): ByteArray = generateBytes(Aes256Key.KEY_BYTES)
}

/** Authenticated-encryption boundary. Implementations own cipher construction and envelope parsing. */
interface AuthenticatedEncryption {
    fun encrypt(plaintext: ByteArray, key: Aes256Key, context: CryptoContext): EncryptedEnvelope
    fun decrypt(envelope: EncryptedEnvelope, key: Aes256Key, context: CryptoContext): ByteArray
}

/** Bounded-memory AES-GCM stream extension. Callers must quarantine output until this returns successfully. */
interface StreamingAuthenticatedEncryption : AuthenticatedEncryption {
    suspend fun encryptStream(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        key: Aes256Key,
        nonce: ByteArray,
        context: CryptoContext,
        authorizationCheckpoint: suspend () -> Boolean,
        onProgress: (Long) -> Unit = {},
    ): StreamCipherSummary

    suspend fun decryptStream(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        key: Aes256Key,
        nonce: ByteArray,
        context: CryptoContext,
        authorizationCheckpoint: suspend () -> Boolean,
    ): StreamCipherSummary
}

data class StreamCipherSummary(val plaintextBytes: Long, val ciphertextBytes: Long)

class StreamAuthorizationExpired : java.util.concurrent.CancellationException("authorization expired")

/** Android Keystore boundary. Keys returned by this interface are non-exportable where the platform allows. */
interface DeviceKeyStore {
    fun createAes256Key(alias: String): Aes256Key
    fun getAes256Key(alias: String): Aes256Key
    fun deleteKey(alias: String)
}

/** Protects high-entropy content keys under independently sourced wrapping keys. */
interface KeyWrappingService {
    fun wrap(
        contentKey: ByteArray,
        wrappingKey: Aes256Key,
        protection: KeyProtection,
        context: CryptoContext,
    ): WrappedKeyEnvelope

    fun unwrap(
        wrappedKey: WrappedKeyEnvelope,
        wrappingKey: Aes256Key,
        expectedProtection: KeyProtection,
        context: CryptoContext,
    ): ByteArray
}

/** PBKDF2 runs on a worker dispatcher; callers can safely invoke this suspend API from UI code. */
interface CredentialKeyDeriver {
    fun newSalt(parameters: KdfParameters = KdfParameters.DEFAULT): ByteArray

    suspend fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        parameters: KdfParameters = KdfParameters.DEFAULT,
    ): ByteArray
}

/** An AES-256 key handle; raw bytes are accepted only at the boundary and are not exposed again. */
class Aes256Key private constructor(
    private val key: SecretKey,
    private val nonceMode: NonceMode,
) {
    internal fun secretKey(): SecretKey = key
    internal fun usesProviderNonce(): Boolean = nonceMode == NonceMode.PROVIDER_GENERATED

    private enum class NonceMode { EXTERNAL_RANDOM, PROVIDER_GENERATED }

    companion object {
        const val KEY_BYTES = 32

        /** Copies and then clears its temporary buffer. The caller remains responsible for clearing [bytes]. */
        fun fromBytes(bytes: ByteArray): Aes256Key {
            if (bytes.size != KEY_BYTES) throw SecurityFailure.InvalidKey()
            val temporary = bytes.copyOf()
            return try {
                Aes256Key(javax.crypto.spec.SecretKeySpec(temporary, "AES"), NonceMode.EXTERNAL_RANDOM)
            } finally {
                temporary.fill(0)
            }
        }

        /** Internal bridge for a key retrieved from AndroidKeyStore; key material is never exported. */
        internal fun fromAndroidKeyStore(key: SecretKey): Aes256Key {
            if (!key.algorithm.equals("AES", ignoreCase = true)) throw SecurityFailure.InvalidKey()
            // The only caller is the Keystore adapter, which provisions this key at 256 bits.
            // Do not call getEncoded(): secret material is intentionally non-exportable.
            return Aes256Key(key, NonceMode.PROVIDER_GENERATED)
        }
    }
}
