package com.ashishkumar.nivara.domain.security

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Version 1 envelope: magic | version | algorithm | nonce length | nonce | ciphertext length | ciphertext+tag. */
class EncryptedEnvelope internal constructor(
    val version: Int,
    val algorithmId: Int,
    nonce: ByteArray,
    ciphertextAndTag: ByteArray,
) {
    private val nonceBytes = nonce.copyOf()
    private val ciphertextBytes = ciphertextAndTag.copyOf()

    val nonce: ByteArray get() = nonceBytes.copyOf()
    val ciphertextAndTag: ByteArray get() = ciphertextBytes.copyOf()

    internal fun nonceForCrypto(): ByteArray = nonceBytes.copyOf()
    internal fun ciphertextForCrypto(): ByteArray = ciphertextBytes.copyOf()

    fun encode(): ByteArray = EncryptedEnvelopeCodec.encode(this)

    companion object {
        const val CURRENT_VERSION = 1
        const val AES_256_GCM_ID = 1
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        internal val MAGIC = byteArrayOf(0x4E, 0x49, 0x56, 0x52) // NIVR

        fun decode(encoded: ByteArray): EncryptedEnvelope = EncryptedEnvelopeCodec.decode(encoded)
    }
}

/** Strict, deterministic binary codec. The GCM tag is already appended to the ciphertext by JCA. */
object EncryptedEnvelopeCodec {
    private const val FIXED_HEADER_BYTES = 4 + 1 + 1 + 1
    private const val LENGTH_BYTES = 4
    private const val MIN_CIPHERTEXT_BYTES = EncryptedEnvelope.TAG_BYTES

    fun encode(envelope: EncryptedEnvelope): ByteArray {
        if (envelope.version != EncryptedEnvelope.CURRENT_VERSION) {
            throw SecurityFailure.UnsupportedEnvelopeVersion()
        }
        if (envelope.algorithmId != EncryptedEnvelope.AES_256_GCM_ID) {
            throw SecurityFailure.UnsupportedAlgorithm()
        }
        val nonce = envelope.nonceForCrypto()
        val ciphertext = envelope.ciphertextForCrypto()
        if (nonce.size != EncryptedEnvelope.NONCE_BYTES || ciphertext.size < MIN_CIPHERTEXT_BYTES) {
            throw SecurityFailure.InvalidEnvelope()
        }
        return ByteBuffer.allocate(FIXED_HEADER_BYTES + nonce.size + LENGTH_BYTES + ciphertext.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(EncryptedEnvelope.MAGIC)
            .put(envelope.version.toByte())
            .put(envelope.algorithmId.toByte())
            .put(nonce.size.toByte())
            .put(nonce)
            .putInt(ciphertext.size)
            .put(ciphertext)
            .array()
    }

    fun decode(encoded: ByteArray): EncryptedEnvelope {
        if (encoded.size < FIXED_HEADER_BYTES + EncryptedEnvelope.NONCE_BYTES + LENGTH_BYTES + MIN_CIPHERTEXT_BYTES) {
            throw SecurityFailure.InvalidEnvelope()
        }
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        repeat(EncryptedEnvelope.MAGIC.size) { index ->
            if (buffer.get() != EncryptedEnvelope.MAGIC[index]) throw SecurityFailure.InvalidEnvelope()
        }
        val version = buffer.get().toInt() and 0xFF
        if (version != EncryptedEnvelope.CURRENT_VERSION) throw SecurityFailure.UnsupportedEnvelopeVersion()
        val algorithm = buffer.get().toInt() and 0xFF
        if (algorithm != EncryptedEnvelope.AES_256_GCM_ID) throw SecurityFailure.UnsupportedAlgorithm()
        val nonceLength = buffer.get().toInt() and 0xFF
        if (nonceLength != EncryptedEnvelope.NONCE_BYTES || buffer.remaining() < nonceLength + LENGTH_BYTES) {
            throw SecurityFailure.InvalidEnvelope()
        }
        val nonce = ByteArray(nonceLength)
        buffer.get(nonce)
        val ciphertextLength = buffer.int
        if (ciphertextLength < MIN_CIPHERTEXT_BYTES || ciphertextLength != buffer.remaining()) {
            nonce.fill(0)
            throw SecurityFailure.InvalidEnvelope()
        }
        val ciphertext = ByteArray(ciphertextLength)
        buffer.get(ciphertext)
        return try {
            EncryptedEnvelope(version, algorithm, nonce, ciphertext)
        } finally {
            nonce.fill(0)
            ciphertext.fill(0)
        }
    }
}
