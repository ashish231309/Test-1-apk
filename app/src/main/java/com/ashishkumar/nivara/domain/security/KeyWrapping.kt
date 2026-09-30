package com.ashishkumar.nivara.domain.security

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Identifies the independent source of a wrapping key; it is authenticated as part of the GCM context. */
enum class KeyProtection(val wireId: Int) {
    CREDENTIAL_DERIVED(1),
    RECOVERY(2),
    ANDROID_KEYSTORE(3);

    companion object {
        internal fun fromWireId(id: Int): KeyProtection = entries.firstOrNull { it.wireId == id }
            ?: throw SecurityFailure.InvalidEnvelope()
    }
}

class WrappedKeyEnvelope internal constructor(
    val protection: KeyProtection,
    val encryptedKey: EncryptedEnvelope,
) {
    fun encode(): ByteArray = WrappedKeyEnvelopeCodec.encode(this)

    companion object {
        fun decode(encoded: ByteArray): WrappedKeyEnvelope = WrappedKeyEnvelopeCodec.decode(encoded)
    }
}

/** Versioned outer descriptor for wrapped data keys; the inner AES-GCM envelope carries its own version. */
object WrappedKeyEnvelopeCodec {
    private val MAGIC = byteArrayOf(0x4E, 0x56, 0x4B, 0x57) // NVKW
    private const val VERSION = 1
    private const val HEADER_BYTES = 4 + 1 + 1 + 4

    fun encode(envelope: WrappedKeyEnvelope): ByteArray {
        val encryptedKey = envelope.encryptedKey.encode()
        return ByteBuffer.allocate(HEADER_BYTES + encryptedKey.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(VERSION.toByte())
            .put(envelope.protection.wireId.toByte())
            .putInt(encryptedKey.size)
            .put(encryptedKey)
            .array()
    }

    fun decode(encoded: ByteArray): WrappedKeyEnvelope {
        if (encoded.size < HEADER_BYTES) throw SecurityFailure.InvalidEnvelope()
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        repeat(MAGIC.size) { index ->
            if (buffer.get() != MAGIC[index]) throw SecurityFailure.InvalidEnvelope()
        }
        val version = buffer.get().toInt() and 0xFF
        if (version != VERSION) throw SecurityFailure.UnsupportedEnvelopeVersion()
        val protection = KeyProtection.fromWireId(buffer.get().toInt() and 0xFF)
        val length = buffer.int
        if (length <= 0 || length != buffer.remaining()) throw SecurityFailure.InvalidEnvelope()
        val inner = ByteArray(length)
        buffer.get(inner)
        return try {
            WrappedKeyEnvelope(protection, EncryptedEnvelope.decode(inner))
        } finally {
            inner.fill(0)
        }
    }
}
