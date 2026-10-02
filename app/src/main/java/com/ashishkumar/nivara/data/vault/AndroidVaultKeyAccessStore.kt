package com.ashishkumar.nivara.data.vault

import android.content.Context
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.VaultKeyAccessRead
import com.ashishkumar.nivara.domain.vault.VaultKeyAccessRecord
import com.ashishkumar.nivara.domain.vault.VaultKeyAccessStore
import java.util.Base64

/**
 * App-private storage for an Android-Keystore-wrapped copy of the existing content key after explicit recovery.
 * The value is ciphertext only. App data removal deletes this record and the OS Keystore alias; no recovery
 * code or plaintext key is stored here.
 */
class AndroidVaultKeyAccessStore(context: Context) : VaultKeyAccessStore {
    private val preferences = context.applicationContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): VaultKeyAccessRead {
        return try {
            val idText = preferences.getString(KEY_VAULT_ID, null)
            val wrapperText = preferences.getString(KEY_WRAPPED_KEY, null)
            if (idText == null && wrapperText == null) return VaultKeyAccessRead.Missing
            if (idText == null || wrapperText == null) return VaultKeyAccessRead.Invalid
            val id = try { VaultId(idText) } catch (_: IllegalArgumentException) { return VaultKeyAccessRead.Invalid }
            val wrapper = try { Base64.getDecoder().decode(wrapperText) }
            catch (_: IllegalArgumentException) { return VaultKeyAccessRead.Invalid }
            if (wrapper.isEmpty() || wrapper.size > MAX_WRAPPER_BYTES) {
                wrapper.fill(0)
                return VaultKeyAccessRead.Invalid
            }
            try { VaultKeyAccessRead.Present(VaultKeyAccessRecord(id, wrapper)) }
            finally { wrapper.fill(0) }
        } catch (_: Exception) {
            VaultKeyAccessRead.Unavailable
        }
    }

    override suspend fun replace(record: VaultKeyAccessRecord): Boolean {
        return try {
            val wrapper = record.encodedWrapper
            try {
                if (wrapper.isEmpty() || wrapper.size > MAX_WRAPPER_BYTES) return false
                preferences.edit()
                    .putString(KEY_VAULT_ID, record.vaultId.value)
                    .putString(KEY_WRAPPED_KEY, Base64.getEncoder().encodeToString(wrapper))
                    .commit()
            } finally { wrapper.fill(0) }
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun clearIfMatches(record: VaultKeyAccessRecord): Boolean {
        return try {
            val currentId = preferences.getString(KEY_VAULT_ID, null)
            val currentWrapper = preferences.getString(KEY_WRAPPED_KEY, null)
            val expected = record.encodedWrapper
            try {
                val current = currentWrapper?.let(Base64.getDecoder()::decode)
                try {
                    if (currentId != record.vaultId.value || current == null || !current.contentEquals(expected)) {
                        return true
                    }
                    preferences.edit().remove(KEY_VAULT_ID).remove(KEY_WRAPPED_KEY).commit()
                } finally { current?.fill(0) }
            } finally { expected.fill(0) }
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val STORE_NAME = "nivara_vault_key_access"
        const val KEY_VAULT_ID = "vault_id"
        const val KEY_WRAPPED_KEY = "wrapped_content_key"
        const val MAX_WRAPPER_BYTES = 8 * 1024
    }
}
