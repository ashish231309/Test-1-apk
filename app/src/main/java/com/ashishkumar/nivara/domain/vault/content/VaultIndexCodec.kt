package com.ashishkumar.nivara.domain.vault.content

import com.ashishkumar.nivara.domain.vault.VaultId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Bounded, deterministic plaintext index codec. Authentication is supplied by [VaultContentCrypto]. */
object VaultIndexCodec {
    private val MAGIC = byteArrayOf(0x4e, 0x56, 0x49, 0x44) // NVID
    const val VERSION = 2
    const val LEGACY_VERSION = 1
    const val MAX_INDEX_BYTES = 8 * 1024 * 1024
    const val MAX_ITEMS = 20_000
    private const val HEADER_BYTES = 4 + 1 + 16 + 8 + 4

    sealed interface DecodeResult {
        data class Decoded(val vaultId: VaultId, val generation: Long, val items: List<VaultItem>) : DecodeResult
        data class Unsupported(val version: Int) : DecodeResult
        data object Invalid : DecodeResult
    }

    fun encode(vaultId: VaultId, generation: Long, items: List<VaultItem>): ByteArray {
        require(generation >= 0 && items.size <= MAX_ITEMS)
        val sorted = items.sortedBy { it.id.value }
        require(sorted.map { it.id }.toSet().size == sorted.size)
        val out = WipingByteArrayOutputStream()
        try {
            DataOutputStream(out).use { data ->
                data.write(MAGIC)
                data.writeByte(VERSION)
                val vaultBytes = vaultId.toBytes()
                try { data.write(vaultBytes) } finally { vaultBytes.fill(0) }
                data.writeLong(generation)
                data.writeInt(sorted.size)
                sorted.forEach { item -> VaultRecordCodec.write(data, item) }
            }
            return out.toByteArray().also { require(it.size in HEADER_BYTES..MAX_INDEX_BYTES) }
        } finally { out.wipe() }
    }

    fun decode(encoded: ByteArray): DecodeResult {
        if (encoded.size !in HEADER_BYTES..MAX_INDEX_BYTES) return DecodeResult.Invalid
        return try {
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                if (!magic.contentEquals(MAGIC)) return DecodeResult.Invalid
                val version = input.readUnsignedByte()
                if (version != VERSION && version != LEGACY_VERSION) return DecodeResult.Unsupported(version)
                val vaultBytes = ByteArray(16).also(input::readFully)
                val vaultId = try { VaultId.fromBytes(vaultBytes) } finally { vaultBytes.fill(0) }
                val generation = input.readLong()
                val count = input.readInt()
                if (generation < 0 || count !in 0..MAX_ITEMS) return DecodeResult.Invalid
                val items = ArrayList<VaultItem>(count)
                val ids = HashSet<VaultItemId>(count)
                repeat(count) {
                    val item = VaultRecordCodec.read(input, version)
                    if (!ids.add(item.id)) return DecodeResult.Invalid
                    items += item
                }
                if (input.available() != 0) return DecodeResult.Invalid
                DecodeResult.Decoded(vaultId, generation, items.toList())
            }
        } catch (_: Exception) {
            DecodeResult.Invalid
        }
    }
}

/** Stable, length-delimited wire representation for a single authenticated index row. */
object VaultRecordCodec {
    private const val MAX_RECORD_BYTES = 8 * 1024

    fun encode(item: VaultItem): ByteArray {
        val out = WipingByteArrayOutputStream()
        try {
            DataOutputStream(out).use { write(it, item) }
            return out.toByteArray().also { require(it.size <= MAX_RECORD_BYTES) }
        } finally { out.wipe() }
    }

    internal fun write(out: DataOutputStream, item: VaultItem) {
        val name = strictUtf8(item.originalFilename) ?: throw IllegalArgumentException("invalid filename")
        val mime = item.originalMimeType?.let(::strictUtf8)
        val key = item.encryptedItemKey
        val record = WipingByteArrayOutputStream()
        try {
            require(name.size in 1..VaultItemValidation.MAX_FILENAME_UTF8_BYTES)
            require(item.originalMimeType == null || mime != null)
            require(key.size in 1..VaultItem.MAX_ENCRYPTED_ITEM_KEY_BYTES)
            DataOutputStream(record).use { data ->
                val id = item.id.toBytes()
                try { data.write(id) } finally { id.fill(0) }
                data.writeShort(name.size)
                data.write(name)
                if (mime == null) data.writeShort(-1) else {
                    data.writeShort(mime.size)
                    data.write(mime)
                }
                data.writeLong(item.originalSizeBytes)
                data.writeLong(item.importedAtEpochMillis)
                data.writeByte(item.objectFormatVersion)
                data.writeLong(item.objectSizeBytes)
                data.writeShort(key.size)
                data.write(key)
                data.writeByte(when (item.lifecycle) {
                    VaultItemLifecycle.ACTIVE -> 0
                    VaultItemLifecycle.TRASHED -> 1
                })
                data.writeLong(item.trashedAtEpochMillis ?: -1L)
                val digest = item.contentDigestSha256
                try {
                    data.writeByte(if (digest == null) 0 else 1)
                    if (digest != null) data.write(digest)
                } finally { digest?.fill(0) }
            }
            val bytes = record.toByteArray()
            try {
                require(bytes.size <= MAX_RECORD_BYTES)
                out.writeInt(bytes.size)
                out.write(bytes)
            } finally { bytes.fill(0) }
        } finally {
            name.fill(0)
            mime?.fill(0)
            key.fill(0)
            record.wipe()
        }
    }

    internal fun read(input: DataInputStream, version: Int = VaultIndexCodec.VERSION): VaultItem {
        val length = input.readInt()
        require(length in 1..MAX_RECORD_BYTES)
        val bytes = ByteArray(length).also(input::readFully)
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                val idBytes = ByteArray(VaultItemId.BYTE_COUNT).also(data::readFully)
                val id = try { VaultItemId.fromBytes(idBytes) } finally { idBytes.fill(0) }
                val nameBytes = readBounded(data, VaultItemValidation.MAX_FILENAME_UTF8_BYTES)
                val filename = try { decodeUtf8(nameBytes) ?: throw IllegalArgumentException("invalid filename") }
                    finally { nameBytes.fill(0) }
                val mimeLength = data.readShort().toInt()
                val mime = when {
                    mimeLength == -1 -> null
                    mimeLength in 1..VaultItemValidation.MAX_MIME_UTF8_BYTES -> {
                        val mimeBytes = ByteArray(mimeLength).also(data::readFully)
                        try { decodeUtf8(mimeBytes) ?: throw IllegalArgumentException("invalid MIME type") }
                        finally { mimeBytes.fill(0) }
                    }
                    else -> throw IllegalArgumentException("invalid MIME length")
                }
                val size = data.readLong()
                val imported = data.readLong()
                val objectVersion = data.readUnsignedByte()
                val objectSize = data.readLong()
                val keyLength = data.readUnsignedShort()
                require(keyLength in 1..VaultItem.MAX_ENCRYPTED_ITEM_KEY_BYTES)
                val key = ByteArray(keyLength).also(data::readFully)
                var digest: ByteArray? = null
                val lifecycle: VaultItemLifecycle
                val trashedAt: Long?
                try {
                    if (version == VaultIndexCodec.LEGACY_VERSION) {
                        lifecycle = VaultItemLifecycle.ACTIVE
                        trashedAt = null
                    } else {
                        lifecycle = when (data.readUnsignedByte()) {
                            0 -> VaultItemLifecycle.ACTIVE
                            1 -> VaultItemLifecycle.TRASHED
                            else -> throw IllegalArgumentException("invalid item lifecycle")
                        }
                        val timestamp = data.readLong()
                        val digestMarker = data.readUnsignedByte()
                        require(digestMarker == 0 || digestMarker == 1)
                        if (digestMarker == 1) digest = ByteArray(VaultItem.SHA_256_BYTES).also(data::readFully)
                        trashedAt = when (lifecycle) {
                            VaultItemLifecycle.ACTIVE -> {
                                require(timestamp == -1L)
                                null
                            }
                            VaultItemLifecycle.TRASHED -> timestamp.also { require(it >= 0) }
                        }
                    }
                    require(data.available() == 0)
                    return VaultItem(
                        id, filename, mime, size, imported, objectVersion, objectSize, key,
                        lifecycle, trashedAt, digest,
                    )
                } finally {
                    key.fill(0)
                    digest?.fill(0)
                }
            }
        } finally { bytes.fill(0) }
    }

    private fun readBounded(input: DataInputStream, maximum: Int): ByteArray {
        val size = input.readUnsignedShort()
        require(size in 1..maximum)
        return ByteArray(size).also(input::readFully)
    }

    private fun strictUtf8(value: String): ByteArray? = try {
        val buffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
        ByteArray(buffer.remaining()).also(buffer::get)
    } catch (_: Exception) { null }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { null }
}

private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
    fun wipe() { buf.fill(0); reset() }
}

/** Plain header for streamed objects. Ciphertext and all header fields are authenticated as GCM AAD. */
object VaultContentObjectCodec {
    private val MAGIC = byteArrayOf(0x4e, 0x56, 0x43, 0x4f) // NVCO
    const val VERSION = 1
    const val ALGORITHM_AES_256_GCM = 1
    const val NONCE_BYTES = 12
    const val HEADER_BYTES = 4 + 1 + 1 + VaultItemId.BYTE_COUNT + 1 + NONCE_BYTES

    data class Header(val version: Int, val itemId: VaultItemId, val nonce: ByteArray, val encoded: ByteArray)

    fun encodeHeader(itemId: VaultItemId, nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES)
        val itemBytes = itemId.toBytes()
        return try {
            ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC).put(VERSION.toByte()).put(ALGORITHM_AES_256_GCM.toByte())
                .put(itemBytes).put(NONCE_BYTES.toByte()).put(nonce).array()
        } finally { itemBytes.fill(0) }
    }

    sealed interface HeaderResult {
        data class Valid(val header: Header) : HeaderResult
        data class Unsupported(val version: Int) : HeaderResult
        data object Invalid : HeaderResult
    }

    fun decodeHeader(encoded: ByteArray): HeaderResult {
        if (encoded.size != HEADER_BYTES) return HeaderResult.Invalid
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC)) return HeaderResult.Invalid
        val version = buffer.get().toInt() and 0xff
        if (version != VERSION) return HeaderResult.Unsupported(version)
        if ((buffer.get().toInt() and 0xff) != ALGORITHM_AES_256_GCM) return HeaderResult.Invalid
        val idBytes = ByteArray(VaultItemId.BYTE_COUNT).also(buffer::get)
        val id = try { VaultItemId.fromBytes(idBytes) } finally { idBytes.fill(0) }
        if ((buffer.get().toInt() and 0xff) != NONCE_BYTES) return HeaderResult.Invalid
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        return HeaderResult.Valid(Header(version, id, nonce, encoded.copyOf()))
    }
}

/** Outer unencrypted framing lets storage select generations and report unsupported formats without decrypting. */
object VaultIndexEnvelopeCodec {
    private val MAGIC = byteArrayOf(0x4e, 0x56, 0x49, 0x58) // NVIX
    const val VERSION = 1
    const val MAX_ENVELOPE_BYTES = VaultIndexCodec.MAX_INDEX_BYTES + 256
    private const val HEADER_BYTES = 4 + 1 + 8 + 4

    data class Encoded(val generation: Long, val encryptedRecord: ByteArray)
    sealed interface DecodeResult {
        data class Valid(val value: Encoded) : DecodeResult
        data class Unsupported(val version: Int) : DecodeResult
        data object Invalid : DecodeResult
    }

    fun encode(generation: Long, encryptedRecord: ByteArray): ByteArray {
        require(generation >= 0 && encryptedRecord.isNotEmpty())
        require(encryptedRecord.size <= MAX_ENVELOPE_BYTES - HEADER_BYTES)
        return ByteBuffer.allocate(HEADER_BYTES + encryptedRecord.size).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC).put(VERSION.toByte()).putLong(generation).putInt(encryptedRecord.size)
            .put(encryptedRecord).array()
    }

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size !in (HEADER_BYTES + 1)..MAX_ENVELOPE_BYTES) return DecodeResult.Invalid
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC)) return DecodeResult.Invalid
        val version = buffer.get().toInt() and 0xff
        if (version != VERSION) return DecodeResult.Unsupported(version)
        val generation = buffer.long
        val length = buffer.int
        if (generation < 0 || length <= 0 || length != buffer.remaining()) return DecodeResult.Invalid
        return DecodeResult.Valid(Encoded(generation, ByteArray(length).also(buffer::get)))
    }
}
