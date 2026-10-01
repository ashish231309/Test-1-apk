package com.ashishkumar.nivara.domain.vault.content

import com.ashishkumar.nivara.domain.vault.VaultId

@JvmInline
value class VaultPresentationHandle(val value: String) {
    init { require(value.matches(Regex("[0-9a-f]{32}"))) }
}

data class VaultImagePixels(val width: Int, val height: Int, val argb: IntArray) {
    init { require(width > 0 && height > 0 && width.toLong() * height == argb.size.toLong()) }
    fun clear() { argb.fill(0) }
}

sealed interface VaultPresentationOpenResult {
    data class Ready(
        val handle: VaultPresentationHandle,
        val previewKind: VaultContentClassification.PreviewKind,
    ) : VaultPresentationOpenResult
    data object Unsupported : VaultPresentationOpenResult
    data object Missing : VaultPresentationOpenResult
    data object Unreadable : VaultPresentationOpenResult
    data object AuthenticationFailed : VaultPresentationOpenResult
    data object UnsupportedVersion : VaultPresentationOpenResult
    data object AuthorizationExpired : VaultPresentationOpenResult
    data object CorruptFormat : VaultPresentationOpenResult
    data object Failed : VaultPresentationOpenResult
}

/** Platform presentation stays behind a narrow boundary; handles carry no URI, path, stream, key, or media object. */
interface VaultContentPresentationGateway {
    suspend fun open(
        vaultId: VaultId,
        itemId: VaultItemId,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultPresentationOpenResult

    suspend fun image(handle: VaultPresentationHandle): VaultImagePixels?
    suspend fun text(handle: VaultPresentationHandle): String?
    fun close(handle: VaultPresentationHandle)
}
