package com.ashishkumar.nivara.domain.vault.content

import com.ashishkumar.nivara.domain.vault.VaultId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Deterministic, bounded plaintext format. Callers must encrypt it before persistence. */
object VaultOrganizationCodec {
    private val MAGIC = byteArrayOf(0x4e, 0x56, 0x4f, 0x52) // NVOR
    const val VERSION = 1
    private const val HEADER_BYTES = 4 + 1 + 16 + 8 + 4

    sealed interface DecodeResult {
        data class Decoded(val vaultId: VaultId, val snapshot: VaultOrganizationSnapshot) : DecodeResult
        data class Unsupported(val version: Int) : DecodeResult
        data object Invalid : DecodeResult
    }

    fun encode(vaultId: VaultId, snapshot: VaultOrganizationSnapshot): ByteArray {
        require(snapshot.generation >= 1)
        val albums = snapshot.albums.sortedBy { it.id.value }
        val output = WipingOutput()
        try {
            DataOutputStream(output).use { data ->
                data.write(MAGIC)
                data.writeByte(VERSION)
                val vaultBytes = vaultId.toBytes()
                try { data.write(vaultBytes) } finally { vaultBytes.fill(0) }
                data.writeLong(snapshot.generation)
                data.writeInt(albums.size)
                albums.forEach { album ->
                    val name = strictUtf8(album.name) ?: throw IllegalArgumentException("invalid album name")
                    try {
                        require(name.size in 1..VaultAlbumName.MAX_UTF8_BYTES)
                        val idBytes = album.id.toBytes()
                        try { data.write(idBytes) } finally { idBytes.fill(0) }
                        data.writeShort(name.size)
                        data.write(name)
                        val memberIds = album.memberItemIds
                        data.writeInt(memberIds.size)
                        memberIds.forEach { itemId ->
                            val bytes = itemId.toBytes()
                            try { data.write(bytes) } finally { bytes.fill(0) }
                        }
                    } finally { name.fill(0) }
                }
            }
            return output.toByteArray().also {
                require(it.size in HEADER_BYTES..VaultOrganizationLimits.MAX_PLAINTEXT_BYTES)
            }
        } finally { output.wipe() }
    }

    fun decode(encoded: ByteArray): DecodeResult {
        if (encoded.size !in HEADER_BYTES..VaultOrganizationLimits.MAX_PLAINTEXT_BYTES) return DecodeResult.Invalid
        return try {
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                if (!magic.contentEquals(MAGIC)) return DecodeResult.Invalid
                val version = input.readUnsignedByte()
                if (version != VERSION) return DecodeResult.Unsupported(version)
                val vaultBytes = ByteArray(16).also(input::readFully)
                val vaultId = try { VaultId.fromBytes(vaultBytes) } finally { vaultBytes.fill(0) }
                val generation = input.readLong()
                val count = input.readInt()
                if (generation < 1 || count !in 0..VaultOrganizationLimits.MAX_ALBUMS) return DecodeResult.Invalid
                val albums = ArrayList<VaultAlbum>(count)
                val albumIds = HashSet<VaultAlbumId>(count)
                var previousAlbumId: String? = null
                var memberships = 0L
                repeat(count) {
                    val idBytes = ByteArray(VaultAlbumId.BYTE_COUNT).also(input::readFully)
                    val id = try { VaultAlbumId.fromBytes(idBytes) } finally { idBytes.fill(0) }
                    if (!albumIds.add(id) || previousAlbumId?.let { id.value <= it } == true) return DecodeResult.Invalid
                    previousAlbumId = id.value
                    val nameLength = input.readUnsignedShort()
                    if (nameLength !in 1..VaultAlbumName.MAX_UTF8_BYTES) return DecodeResult.Invalid
                    val nameBytes = ByteArray(nameLength).also(input::readFully)
                    val name = try { decodeUtf8(nameBytes) ?: return DecodeResult.Invalid }
                        finally { nameBytes.fill(0) }
                    if (VaultAlbumName.normalize(name) != name) return DecodeResult.Invalid
                    val memberCount = input.readInt()
                    if (memberCount !in 0..VaultOrganizationLimits.MAX_ITEMS_PER_ALBUM) return DecodeResult.Invalid
                    memberships += memberCount.toLong()
                    if (memberships > VaultOrganizationLimits.MAX_TOTAL_MEMBERSHIPS) return DecodeResult.Invalid
                    val members = ArrayList<VaultItemId>(memberCount)
                    val seenMembers = HashSet<VaultItemId>(memberCount)
                    repeat(memberCount) {
                        val memberBytes = ByteArray(VaultItemId.BYTE_COUNT).also(input::readFully)
                        val memberId = try { VaultItemId.fromBytes(memberBytes) } finally { memberBytes.fill(0) }
                        if (!seenMembers.add(memberId)) return DecodeResult.Invalid
                        members += memberId
                    }
                    albums += VaultAlbum(id, name, members)
                }
                if (input.available() != 0) return DecodeResult.Invalid
                DecodeResult.Decoded(vaultId, VaultOrganizationSnapshot(generation, albums))
            }
        } catch (_: Exception) {
            DecodeResult.Invalid
        }
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

/** Visible only as ciphertext. The generation is also authenticated as AES-GCM context. */
object VaultOrganizationEnvelopeCodec {
    private val MAGIC = byteArrayOf(0x4e, 0x56, 0x4f, 0x45) // NVOE
    const val VERSION = 1
    private const val HEADER_BYTES = 4 + 1 + 8 + 4

    data class Record(val generation: Long, val encryptedEnvelope: ByteArray)
    sealed interface DecodeResult {
        data class Valid(val record: Record) : DecodeResult
        data class Unsupported(val version: Int) : DecodeResult
        data object Invalid : DecodeResult
    }

    fun encode(generation: Long, encryptedEnvelope: ByteArray): ByteArray {
        require(generation >= 1)
        require(encryptedEnvelope.isNotEmpty() && encryptedEnvelope.size <= VaultOrganizationLimits.MAX_STORED_RECORD_BYTES)
        val output = WipingOutput()
        try {
            DataOutputStream(output).use { data ->
                data.write(MAGIC)
                data.writeByte(VERSION)
                data.writeLong(generation)
                data.writeInt(encryptedEnvelope.size)
                data.write(encryptedEnvelope)
            }
            return output.toByteArray().also { require(it.size <= VaultOrganizationLimits.MAX_STORED_RECORD_BYTES) }
        } finally { output.wipe() }
    }

    fun decode(encoded: ByteArray): DecodeResult {
        if (encoded.size !in HEADER_BYTES + 1..VaultOrganizationLimits.MAX_STORED_RECORD_BYTES) return DecodeResult.Invalid
        return try {
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                if (!magic.contentEquals(MAGIC)) return DecodeResult.Invalid
                val version = input.readUnsignedByte()
                if (version != VERSION) return DecodeResult.Unsupported(version)
                val generation = input.readLong()
                val length = input.readInt()
                if (generation < 1 || length !in 1..VaultOrganizationLimits.MAX_STORED_RECORD_BYTES ||
                    length != input.available()
                ) return DecodeResult.Invalid
                DecodeResult.Valid(Record(generation, ByteArray(length).also(input::readFully)))
            }
        } catch (_: Exception) { DecodeResult.Invalid }
    }
}

private class WipingOutput : ByteArrayOutputStream() {
    fun wipe() { buf.fill(0); reset() }
}
