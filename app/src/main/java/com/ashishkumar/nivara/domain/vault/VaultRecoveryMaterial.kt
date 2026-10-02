package com.ashishkumar.nivara.domain.vault

import java.util.zip.CRC32

/**
 * Human-transfer representation for one 256-bit random recovery key. The checksum is error detection only;
 * authenticity comes from the existing AES-GCM WrappedKeyEnvelope and the authenticated vault header.
 */
object VaultRecoveryCodeCodec {
    const val KEY_BYTES = 32
    const val MAX_INPUT_CHARS = 128
    private const val PREFIX = "NVR1"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val KEY_CHARS = 52
    private const val CHECK_CHARS = 4

    sealed interface Decode {
        class Valid internal constructor(key: ByteArray) : Decode {
            private val keyBytes = key.copyOf()
            fun keyBytes(): ByteArray = keyBytes.copyOf()
            fun clear() = keyBytes.fill(0)
            override fun toString(): String = "Valid(recovery key redacted)"
        }
        data object Invalid : Decode
    }

    /** Returns a versioned, grouped code; the caller owns and must clear [keyBytes]. */
    fun encode(keyBytes: ByteArray): String {
        require(keyBytes.size == KEY_BYTES)
        val checksum = checksumChars(keyBytes)
        val result = StringBuilder(PREFIX.length + 1 + KEY_CHARS + KEY_CHARS / 4 + 1 + CHECK_CHARS)
            .append(PREFIX).append('-')
        var encodedChars = 0
        fun appendEncoded(value: Int) {
            if (encodedChars > 0 && encodedChars % 4 == 0) result.append('-')
            result.append(ALPHABET[value])
            encodedChars++
        }
        var buffer = 0
        var bits = 0
        keyBytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                appendEncoded((buffer ushr bits) and 31)
            }
        }
        if (bits > 0) appendEncoded((buffer shl (5 - bits)) and 31)
        check(encodedChars == KEY_CHARS)
        return result.append('-').append(checksum).toString()
    }

    /** Input is bounded first and normalized only in a mutable buffer that is cleared before returning. */
    fun decode(input: CharSequence): Decode {
        if (input.length !in 1..MAX_INPUT_CHARS) return Decode.Invalid
        val compact = CharArray(input.length)
        var compactLength = 0
        try {
            for (character in input) {
                when {
                    character == '-' || character == ' ' || character == '\t' || character == '\n' || character == '\r' -> Unit
                    character.code > 0x7f -> return Decode.Invalid
                    else -> compact[compactLength++] = character.uppercaseChar()
                }
            }
            if (compactLength != PREFIX.length + KEY_CHARS + CHECK_CHARS) return Decode.Invalid
            for (index in PREFIX.indices) if (compact[index] != PREFIX[index]) return Decode.Invalid
            for (index in PREFIX.length until PREFIX.length + KEY_CHARS) {
                if (compact[index] !in ALPHABET) return Decode.Invalid
            }
            val key = base32Decode(compact, PREFIX.length) ?: return Decode.Invalid
            try {
                val expectedChecksum = checksumChars(key)
                for (index in 0 until CHECK_CHARS) {
                    if (compact[PREFIX.length + KEY_CHARS + index] != expectedChecksum[index]) return Decode.Invalid
                }
                return Decode.Valid(key)
            } finally {
                key.fill(0)
            }
        } finally {
            compact.fill('\u0000')
        }
    }

    private fun base32Decode(text: CharArray, start: Int): ByteArray? {
        if (start < 0 || start + KEY_CHARS > text.size) return null
        val output = ByteArray(KEY_BYTES)
        var outputOffset = 0
        var buffer = 0
        var bits = 0
        for (index in start until start + KEY_CHARS) {
            val character = text[index]
            val value = ALPHABET.indexOf(character)
            if (value < 0) {
                output.fill(0)
                return null
            }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                if (outputOffset >= output.size) {
                    output.fill(0)
                    return null
                }
                output[outputOffset++] = (buffer ushr bits).toByte()
            }
        }
        if (outputOffset != KEY_BYTES || bits != 4 || (buffer and ((1 shl bits) - 1)) != 0) {
            output.fill(0)
            return null
        }
        return output
    }

    private fun checksumChars(bytes: ByteArray): String {
        val value = (CRC32().apply { update(bytes) }.value ushr 12) and 0xfffffL
        return CharArray(CHECK_CHARS) { index ->
            val shift = (CHECK_CHARS - index - 1) * 5
            ALPHABET[((value ushr shift) and 31L).toInt()]
        }.concatToString()
    }
}

/** Versioned external recovery slot metadata around the Stage 2 WrappedKeyEnvelope bytes. */
class VaultRecoveryRecord(
    val vaultId: VaultId,
    wrappedContentKey: ByteArray,
) {
    private val envelopeBytes = wrappedContentKey.copyOf()
    val wrappedContentKey: ByteArray get() = envelopeBytes.copyOf()
    override fun toString(): String = "VaultRecoveryRecord(vaultId=$vaultId, envelope=redacted)"
}

sealed interface VaultRecoveryRecordDecode {
    data class Supported(val record: VaultRecoveryRecord) : VaultRecoveryRecordDecode
    data class UnsupportedVersion(val version: Int) : VaultRecoveryRecordDecode
    data object Invalid : VaultRecoveryRecordDecode
}

/** Strict framing only; encryption and authentication remain exclusively in WrappedKeyEnvelope. */
object VaultRecoveryRecordCodec {
    const val CURRENT_VERSION = 1
    const val MAX_RECORD_BYTES = 16 * 1024
    private const val FIXED_BYTES = 4 + 1 + 16 + 4
    private const val MAX_WRAPPED_KEY_BYTES = 8 * 1024
    private val MAGIC = byteArrayOf(0x4e, 0x56, 0x52, 0x43) // NVRC

    fun encode(record: VaultRecoveryRecord): ByteArray {
        val envelope = record.wrappedContentKey
        require(envelope.isNotEmpty() && envelope.size <= MAX_WRAPPED_KEY_BYTES)
        val id = record.vaultId.toBytes()
        return try {
            java.nio.ByteBuffer.allocate(FIXED_BYTES + envelope.size)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .put(MAGIC)
                .put(CURRENT_VERSION.toByte())
                .put(id)
                .putInt(envelope.size)
                .put(envelope)
                .array()
                .also { require(it.size <= MAX_RECORD_BYTES) }
        } finally {
            id.fill(0)
            envelope.fill(0)
        }
    }

    fun decode(encoded: ByteArray): VaultRecoveryRecordDecode {
        if (encoded.size !in FIXED_BYTES + 1..MAX_RECORD_BYTES) return VaultRecoveryRecordDecode.Invalid
        val buffer = java.nio.ByteBuffer.wrap(encoded).order(java.nio.ByteOrder.BIG_ENDIAN)
        for (expected in MAGIC) if (buffer.get() != expected) return VaultRecoveryRecordDecode.Invalid
        val version = buffer.get().toInt() and 0xff
        if (version != CURRENT_VERSION) return VaultRecoveryRecordDecode.UnsupportedVersion(version)
        val idBytes = ByteArray(16)
        buffer.get(idBytes)
        return try {
            val length = buffer.int
            if (length !in 1..MAX_WRAPPED_KEY_BYTES || length != buffer.remaining()) {
                return VaultRecoveryRecordDecode.Invalid
            }
            val envelope = ByteArray(length)
            buffer.get(envelope)
            try {
                VaultRecoveryRecordDecode.Supported(
                    VaultRecoveryRecord(VaultId.fromBytes(idBytes), envelope),
                )
            } finally {
                envelope.fill(0)
            }
        } catch (_: IllegalArgumentException) {
            VaultRecoveryRecordDecode.Invalid
        } catch (_: java.nio.BufferUnderflowException) {
            VaultRecoveryRecordDecode.Invalid
        } finally {
            idBytes.fill(0)
        }
    }
}
