package com.ashishkumar.nivara.domain.vault

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opaque cryptographic envelopes carried by the versioned external metadata record. */
class VaultMetadataEnvelope(
    val vaultId: VaultId,
    wrappedContentKey: ByteArray,
    encryptedHeader: ByteArray,
) {
    private val wrappedBytes = wrappedContentKey.copyOf()
    private val headerBytes = encryptedHeader.copyOf()
    val wrappedContentKey: ByteArray get() = wrappedBytes.copyOf()
    val encryptedHeader: ByteArray get() = headerBytes.copyOf()
}

sealed interface VaultMetadataDecode {
    data class Supported(val metadata: VaultMetadataEnvelope) : VaultMetadataDecode
    data class UnsupportedVersion(val version: Int) : VaultMetadataDecode
    data object Invalid : VaultMetadataDecode
}

/** Strict outer record: magic | format | vault-id | wrapped-key length+bytes | encrypted-header length+bytes. */
object VaultMetadataCodec {
    const val CURRENT_VERSION = 1
    const val MAX_METADATA_BYTES = 16 * 1024
    private const val FIXED_BYTES = 8 + 4 + 16 + 4 + 4
    private const val MAX_ENVELOPE_BYTES = 8 * 1024
    private val MAGIC = byteArrayOf(0x4e, 0x49, 0x56, 0x4c, 0x54, 0x31, 0x33, 0x4d) // NIVLT13M

    fun encode(metadata: VaultMetadataEnvelope): ByteArray {
        val wrapped = metadata.wrappedContentKey
        val header = metadata.encryptedHeader
        require(wrapped.isNotEmpty() && wrapped.size <= MAX_ENVELOPE_BYTES)
        require(header.isNotEmpty() && header.size <= MAX_ENVELOPE_BYTES)
        val id = metadata.vaultId.toBytes()
        return try {
            ByteBuffer.allocate(FIXED_BYTES + wrapped.size + header.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC)
                .putInt(CURRENT_VERSION)
                .put(id)
                .putInt(wrapped.size)
                .put(wrapped)
                .putInt(header.size)
                .put(header)
                .array()
                .also { require(it.size <= MAX_METADATA_BYTES) }
        } finally {
            id.fill(0)
            wrapped.fill(0)
            header.fill(0)
        }
    }

    fun decode(encoded: ByteArray): VaultMetadataDecode {
        if (encoded.size < FIXED_BYTES || encoded.size > MAX_METADATA_BYTES) return VaultMetadataDecode.Invalid
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        for (expected in MAGIC) {
            if (!buffer.hasRemaining() || buffer.get() != expected) return VaultMetadataDecode.Invalid
        }
        val version = buffer.int
        if (version != CURRENT_VERSION) return VaultMetadataDecode.UnsupportedVersion(version)
        if (buffer.remaining() < 16 + 4 + 4) return VaultMetadataDecode.Invalid
        val id = ByteArray(16)
        buffer.get(id)
        return try {
            val wrappedLength = buffer.int
            if (wrappedLength !in 1..MAX_ENVELOPE_BYTES || wrappedLength > buffer.remaining() - 4) {
                return VaultMetadataDecode.Invalid
            }
            val wrapped = ByteArray(wrappedLength)
            buffer.get(wrapped)
            val headerLength = buffer.int
            if (headerLength !in 1..MAX_ENVELOPE_BYTES || headerLength != buffer.remaining()) {
                wrapped.fill(0)
                return VaultMetadataDecode.Invalid
            }
            val header = ByteArray(headerLength)
            buffer.get(header)
            try {
                VaultMetadataDecode.Supported(
                    VaultMetadataEnvelope(VaultId.fromBytes(id), wrapped, header),
                )
            } finally {
                wrapped.fill(0)
                header.fill(0)
            }
        } catch (_: IllegalArgumentException) {
            VaultMetadataDecode.Invalid
        } catch (_: java.nio.BufferUnderflowException) {
            VaultMetadataDecode.Invalid
        } finally {
            id.fill(0)
        }
    }
}

sealed interface VaultHeaderDecode {
    data class Supported(val vaultId: VaultId) : VaultHeaderDecode
    data class UnsupportedVersion(val version: Int) : VaultHeaderDecode
    data object Invalid : VaultHeaderDecode
}

/** Plaintext is intentionally limited to authenticated format markers and the non-secret vault identifier. */
object VaultHeaderCodec {
    const val CURRENT_VERSION = 1
    private val MAGIC = byteArrayOf(0x4e, 0x56, 0x48, 0x44, 0x52, 0x31, 0x33, 0x00) // NVHDR13\0
    private const val HEADER_BYTES = 8 + 4 + 16

    fun encode(vaultId: VaultId): ByteArray {
        val id = vaultId.toBytes()
        return try {
            ByteBuffer.allocate(HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC)
                .putInt(CURRENT_VERSION)
                .put(id)
                .array()
        } finally {
            id.fill(0)
        }
    }

    fun decode(bytes: ByteArray): VaultHeaderDecode {
        if (bytes.size != HEADER_BYTES) return VaultHeaderDecode.Invalid
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        for (expected in MAGIC) {
            if (buffer.get() != expected) return VaultHeaderDecode.Invalid
        }
        val version = buffer.int
        if (version != CURRENT_VERSION) return VaultHeaderDecode.UnsupportedVersion(version)
        val id = ByteArray(16)
        buffer.get(id)
        return try {
            VaultHeaderDecode.Supported(VaultId.fromBytes(id))
        } finally {
            id.fill(0)
        }
    }
}
