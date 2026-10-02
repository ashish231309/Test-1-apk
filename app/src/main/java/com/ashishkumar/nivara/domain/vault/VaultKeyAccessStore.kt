package com.ashishkumar.nivara.domain.vault

/** Opaque per-installation wrapper state. It is ciphertext under Android Keystore, never a plaintext vault key. */
class VaultKeyAccessRecord(val vaultId: VaultId, encodedWrapper: ByteArray) {
    private val bytes = encodedWrapper.copyOf()
    val encodedWrapper: ByteArray get() = bytes.copyOf()
    override fun toString(): String = "VaultKeyAccessRecord(vaultId=$vaultId, wrapper=redacted)"
}

sealed interface VaultKeyAccessRead {
    data object Missing : VaultKeyAccessRead
    data class Present(val record: VaultKeyAccessRecord) : VaultKeyAccessRead
    data object Invalid : VaultKeyAccessRead
    data object Unavailable : VaultKeyAccessRead
}

/** Stores only an authenticated device-wrapped content key for the current installation. */
interface VaultKeyAccessStore {
    suspend fun load(): VaultKeyAccessRead
    suspend fun replace(record: VaultKeyAccessRecord): Boolean
    suspend fun clearIfMatches(record: VaultKeyAccessRecord): Boolean
}
