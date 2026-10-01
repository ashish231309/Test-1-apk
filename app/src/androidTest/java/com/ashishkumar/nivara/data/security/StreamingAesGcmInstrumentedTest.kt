package com.ashishkumar.nivara.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ashishkumar.nivara.domain.security.Aes256Key
import com.ashishkumar.nivara.domain.security.CryptoContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class StreamingAesGcmInstrumentedTest {
    @Test fun androidProviderStreamsLargeGcmObjectAndAuthenticatesBeforeReturn() = runBlocking {
        val random = JcaSecureRandomSource()
        val crypto = JcaAesGcmEncryption(random)
        val source = ByteArray(4 * 1024 * 1024 + 73) { ((it * 17) and 0xff).toByte() }
        val keyBytes = random.generateAes256KeyBytes()
        val key = Aes256Key.fromBytes(keyBytes)
        keyBytes.fill(0)
        val nonce = random.generateGcmNonce()
        val context = CryptoContext("nivara.vault.content-object.v1", ByteArray(24) { it.toByte() })
        val encrypted = ByteArrayOutputStream()
        val write = crypto.encryptStream(
            ByteArrayInputStream(source), encrypted, key, nonce, context, { true },
        )
        assertEquals(source.size.toLong(), write.plaintextBytes)
        assertEquals(source.size + 16L, write.ciphertextBytes)
        val recovered = ByteArrayOutputStream()
        val read = crypto.decryptStream(
            ByteArrayInputStream(encrypted.toByteArray()), recovered, key, nonce, context, { true },
        )
        assertEquals(source.size.toLong(), read.plaintextBytes)
        assertTrue(source.contentEquals(recovered.toByteArray()))
        nonce.fill(0)
    }

    @Test fun secureNonceSourceProducesUniqueTwelveByteReservationsInSample() {
        val random = JcaSecureRandomSource()
        val seen = HashSet<String>()
        repeat(1_000) {
            val nonce = random.generateGcmNonce()
            assertEquals(12, nonce.size)
            assertTrue(seen.add(nonce.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }))
            nonce.fill(0)
        }
    }
}
