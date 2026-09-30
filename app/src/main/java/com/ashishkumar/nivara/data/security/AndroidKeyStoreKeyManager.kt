package com.ashishkumar.nivara.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.ashishkumar.nivara.domain.security.Aes256Key
import com.ashishkumar.nivara.domain.security.DeviceKeyStore
import com.ashishkumar.nivara.domain.security.SecurityFailure
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** AndroidKeyStore-backed AES key access. Key material is kept non-exportable by the platform. */
class AndroidKeyStoreKeyManager : DeviceKeyStore {
    override fun createAes256Key(alias: String): Aes256Key {
        val fullAlias = keyAlias(alias)
        try {
            val store = loadStore()
            if (store.containsAlias(fullAlias)) throw SecurityFailure.KeyAlreadyExists()
            val generator = KeyGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEY_STORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    fullAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(Aes256Key.KEY_BYTES * Byte.SIZE_BITS)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            val key = generator.generateKey() as? SecretKey ?: throw SecurityFailure.KeyGenerationFailed()
            return asAes256Key(key)
        } catch (failure: SecurityFailure) {
            throw failure
        } catch (failure: GeneralSecurityException) {
            throw SecurityFailure.KeyGenerationFailed()
        } catch (failure: IOException) {
            throw SecurityFailure.KeyGenerationFailed()
        } catch (failure: IllegalArgumentException) {
            throw SecurityFailure.InvalidParameters()
        }
    }

    override fun getAes256Key(alias: String): Aes256Key {
        val fullAlias = keyAlias(alias)
        try {
            val key = loadStore().getKey(fullAlias, null)
                ?: throw SecurityFailure.MissingKey()
            val secretKey = key as? SecretKey ?: throw SecurityFailure.InvalidKey()
            return asAes256Key(secretKey)
        } catch (failure: SecurityFailure) {
            throw failure
        } catch (failure: KeyPermanentlyInvalidatedException) {
            throw SecurityFailure.KeyInvalidated()
        } catch (failure: UnrecoverableKeyException) {
            throw SecurityFailure.KeyInvalidated()
        } catch (failure: GeneralSecurityException) {
            throw SecurityFailure.CryptoOperationFailed()
        } catch (failure: IOException) {
            throw SecurityFailure.CryptoOperationFailed()
        }
    }

    override fun deleteKey(alias: String) {
        val fullAlias = keyAlias(alias)
        try {
            loadStore().deleteEntry(fullAlias)
        } catch (failure: GeneralSecurityException) {
            throw SecurityFailure.CryptoOperationFailed()
        } catch (failure: IOException) {
            throw SecurityFailure.CryptoOperationFailed()
        }
    }

    private fun asAes256Key(key: SecretKey): Aes256Key {
        val keyInfo = SecretKeyFactory.getInstance(KEY_ALGORITHM, ANDROID_KEY_STORE)
            .getKeySpec(key, KeyInfo::class.java)
        if (keyInfo.keySize != Aes256Key.KEY_BYTES * Byte.SIZE_BITS) {
            throw SecurityFailure.InvalidKey()
        }
        return Aes256Key.fromAndroidKeyStore(key)
    }

    private fun keyAlias(alias: String): String {
        if (!ALIAS_PATTERN.matches(alias)) throw SecurityFailure.InvalidParameters()
        return "$ALIAS_PREFIX$alias"
    }

    private fun loadStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    } catch (failure: GeneralSecurityException) {
        throw SecurityFailure.CryptoOperationFailed()
    } catch (failure: IOException) {
        throw SecurityFailure.CryptoOperationFailed()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALGORITHM = "AES"
        const val ALIAS_PREFIX = "nivara."
        val ALIAS_PATTERN = Regex("[A-Za-z0-9._-]{1,96}")
    }
}
