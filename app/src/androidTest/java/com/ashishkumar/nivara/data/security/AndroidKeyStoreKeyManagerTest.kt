package com.ashishkumar.nivara.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ashishkumar.nivara.domain.security.CryptoContext
import com.ashishkumar.nivara.domain.security.SecurityFailure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeyStoreKeyManagerTest {
    @Test
    fun keystoreGeneratedKeyEncryptsDecryptsAndCanBeRemoved() {
        val manager = AndroidKeyStoreKeyManager()
        val alias = "stage2.instrumentation.aesgcm"
        manager.deleteKey(alias)
        try {
            val generated = manager.createAes256Key(alias)
            val loaded = manager.getAes256Key(alias)
            val encryption = JcaAesGcmEncryption(JcaSecureRandomSource())
            val plaintext = byteArrayOf(4, 2, 9, 1)
            val context = CryptoContext("instrumentation/key-store")
            val envelope = encryption.encrypt(plaintext, generated, context)

            assertArrayEquals(plaintext, encryption.decrypt(envelope, loaded, context))
        } finally {
            manager.deleteKey(alias)
        }
        assertThrows(SecurityFailure.MissingKey::class.java) { manager.getAes256Key(alias) }
    }
}
