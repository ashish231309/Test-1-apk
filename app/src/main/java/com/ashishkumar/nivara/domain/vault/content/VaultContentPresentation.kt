package com.ashishkumar.nivara.domain.vault.content

/** Presentation-only MIME classification. It is intentionally not serialized into VaultItem or the index. */
enum class VaultContentCategory { IMAGE, VIDEO, AUDIO, DOCUMENT, OTHER }

data class VaultContentClassification(
    val category: VaultContentCategory,
    val mimeType: String?,
    val previewKind: PreviewKind,
) {
    enum class PreviewKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, UNSUPPORTED }
}

object VaultContentClassifier {
    fun classify(item: VaultItem): VaultContentClassification = classify(item.originalMimeType)

    fun classify(rawMimeType: String?): VaultContentClassification {
        val mime = rawMimeType?.takeIf(VaultItemValidation::validateMimeType)
            ?.lowercase(java.util.Locale.ROOT)
            ?: return VaultContentClassification(VaultContentCategory.OTHER, null, VaultContentClassification.PreviewKind.UNSUPPORTED)
        val preview = when (mime) {
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp", "image/heic", "image/heif", "image/avif" ->
                VaultContentClassification.PreviewKind.IMAGE
            "video/mp4", "video/webm", "video/3gpp", "video/3gpp2", "video/matroska", "video/x-matroska" ->
                VaultContentClassification.PreviewKind.VIDEO
            "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/wav", "audio/x-wav", "audio/flac", "audio/x-flac", "audio/ogg", "audio/opus", "audio/3gpp" ->
                VaultContentClassification.PreviewKind.AUDIO
            "application/pdf" -> VaultContentClassification.PreviewKind.PDF
            "text/plain" -> VaultContentClassification.PreviewKind.TEXT
            else -> VaultContentClassification.PreviewKind.UNSUPPORTED
        }
        val category = when {
            mime.startsWith("image/") -> VaultContentCategory.IMAGE
            mime.startsWith("video/") -> VaultContentCategory.VIDEO
            mime.startsWith("audio/") -> VaultContentCategory.AUDIO
            mime.startsWith("text/") || mime == "application/pdf" || mime == "application/msword" ||
                mime == "application/vnd.ms-excel" || mime == "application/vnd.ms-powerpoint" ||
                mime.startsWith("application/vnd.openxmlformats-officedocument.") ||
                mime.startsWith("application/vnd.oasis.opendocument.") -> VaultContentCategory.DOCUMENT
            else -> VaultContentCategory.OTHER
        }
        return VaultContentClassification(category, mime, preview)
    }
}

/** Stable states for the item-open flow; no streams, keys, or decrypted buffers are carried here. */
sealed interface VaultItemOpenState {
    data object Idle : VaultItemOpenState
    data object Authorizing : VaultItemOpenState
    data object Opening : VaultItemOpenState
    data object Decrypting : VaultItemOpenState
    data object Rendering : VaultItemOpenState
    data object Unsupported : VaultItemOpenState
    data object UnsupportedVersion : VaultItemOpenState
    data object Missing : VaultItemOpenState
    data object Unreadable : VaultItemOpenState
    data object AuthenticationFailed : VaultItemOpenState
    data object CorruptFormat : VaultItemOpenState
    data object Failed : VaultItemOpenState
    data object Locked : VaultItemOpenState
    data object Closed : VaultItemOpenState
}
