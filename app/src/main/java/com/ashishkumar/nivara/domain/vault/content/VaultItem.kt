package com.ashishkumar.nivara.domain.vault.content

/** Opaque identifier used for index identity and generated object names; never derived from a filename. */
@JvmInline
value class VaultItemId(val value: String) {
    init {
        require(value.matches(Regex("[0-9a-f]{32}")))
    }

    fun toBytes(): ByteArray = ByteArray(BYTE_COUNT) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    companion object {
        const val BYTE_COUNT = 16
        fun fromBytes(bytes: ByteArray): VaultItemId {
            require(bytes.size == BYTE_COUNT)
            return VaultItemId(bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        }
    }
}

/** Domain-only immutable row. The encrypted per-item key is a Stage 2 AEAD envelope under the vault key. */
class VaultItem(
    val id: VaultItemId,
    val originalFilename: String,
    val originalMimeType: String?,
    val originalSizeBytes: Long,
    val importedAtEpochMillis: Long,
    val objectFormatVersion: Int,
    val objectSizeBytes: Long,
    encryptedItemKey: ByteArray,
) {
    private val encryptedItemKeyBytes = encryptedItemKey.copyOf()
    val encryptedItemKey: ByteArray get() = encryptedItemKeyBytes.copyOf()

    init {
        require(VaultItemValidation.validateFilename(originalFilename))
        require(originalMimeType == null || VaultItemValidation.validateMimeType(originalMimeType))
        require(originalSizeBytes >= 0)
        require(importedAtEpochMillis >= 0)
        require(objectFormatVersion > 0)
        require(objectSizeBytes >= VaultContentObjectCodec.HEADER_BYTES + 16)
        require(encryptedItemKeyBytes.isNotEmpty() && encryptedItemKeyBytes.size <= MAX_ENCRYPTED_ITEM_KEY_BYTES)
    }

    companion object {
        const val MAX_ENCRYPTED_ITEM_KEY_BYTES = 4 * 1024
    }
}

object VaultItemValidation {
    const val MAX_FILENAME_UTF8_BYTES = 255
    const val MAX_MIME_UTF8_BYTES = 255

    fun validateFilename(value: String): Boolean {
        if (value.isBlank() || value == "." || value == ".." || ".." in value) return false
        if (value.any { it.isISOControl() || it == '/' || it == '\\' }) return false
        return try {
            val encoded = Charsets.UTF_8.newEncoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value))
            encoded.remaining() in 1..MAX_FILENAME_UTF8_BYTES
        } catch (_: Exception) { false }
    }

    fun validateMimeType(value: String): Boolean =
        value.length <= MAX_MIME_UTF8_BYTES && MIME_PATTERN.matches(value)

    private val MIME_PATTERN = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
}
