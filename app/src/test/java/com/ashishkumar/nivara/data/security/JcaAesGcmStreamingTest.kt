package com.ashishkumar.nivara.data.security

import com.ashishkumar.nivara.domain.security.Aes256Key
import com.ashishkumar.nivara.domain.security.CryptoContext
import com.ashishkumar.nivara.domain.security.SecurityFailure
import com.ashishkumar.nivara.domain.security.SecureRandomSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class JcaAesGcmStreamingTest {
    @Test fun largeInputStreamsRoundTripWithoutReadAllBytes() = runBlocking {
        val plain = ByteArray(1_400_123) { ((it * 31) and 0xff).toByte() }
        val keyBytes = ByteArray(32) { (it + 1).toByte() }
        val key = Aes256Key.fromBytes(keyBytes)
        val nonce = ByteArray(12) { (it + 7).toByte() }
        val context = CryptoContext("test.content-object.v1", byteArrayOf(4, 5, 6))
        val crypto = JcaAesGcmEncryption(DeterministicRandom())
        val encrypted = ByteArrayOutputStream()
        val encryption = crypto.encryptStream(
            input = GuardedInputStream(plain),
            output = encrypted,
            key = key,
            nonce = nonce,
            context = context,
            authorizationCheckpoint = { true },
        )
        assertEquals(plain.size.toLong(), encryption.plaintextBytes)
        assertEquals(plain.size + 16L, encryption.ciphertextBytes)

        val decrypted = ByteArrayOutputStream()
        val decryption = crypto.decryptStream(
            input = GuardedInputStream(encrypted.toByteArray()),
            output = decrypted,
            key = key,
            nonce = nonce,
            context = context,
            authorizationCheckpoint = { true },
        )
        assertEquals(plain.size.toLong(), decryption.plaintextBytes)
        assertEquals(plain.size + 16L, decryption.ciphertextBytes)
        assertTrue(plain.contentEquals(decrypted.toByteArray()))
    }

    @Test fun tamperedAndTruncatedStreamsFailAuthentication() = runBlocking {
        val key = Aes256Key.fromBytes(ByteArray(32) { 0x55 })
        val nonce = ByteArray(12) { 0x33 }
        val context = CryptoContext("test.content-object.v1", byteArrayOf(9))
        val crypto = JcaAesGcmEncryption(DeterministicRandom())
        val encrypted = ByteArrayOutputStream()
        crypto.encryptStream(
            ByteArrayInputStream(ByteArray(90_000) { 0x24 }), encrypted, key, nonce, context, { true },
        )
        val tampered = encrypted.toByteArray().also { it[it.lastIndex] = (it.last().toInt() xor 0x10).toByte() }
        val truncated = encrypted.toByteArray().copyOf(encrypted.size() - 1)
        listOf(tampered, truncated).forEach { bytes ->
            val result = runCatching {
                crypto.decryptStream(ByteArrayInputStream(bytes), ByteArrayOutputStream(), key, nonce, context, { true })
            }.exceptionOrNull()
            assertTrue(result is SecurityFailure.AuthenticationFailed)
        }
    }

    @Test fun cancellationCheckpointAbortsBeforeSuccessfulFinalization() = runBlocking {
        val key = Aes256Key.fromBytes(ByteArray(32) { 0x61 })
        val crypto = JcaAesGcmEncryption(DeterministicRandom())
        var checks = 0
        val result = runCatching {
            crypto.encryptStream(
                ByteArrayInputStream(ByteArray(300_000)), ByteArrayOutputStream(), key,
                ByteArray(12) { 0x22 }, CryptoContext("test.content-object.v1"),
                authorizationCheckpoint = { ++checks < 2 },
            )
        }.exceptionOrNull()
        assertTrue(result is java.util.concurrent.CancellationException)
    }

    @Test fun nonceSourceReturnsDistinctValuesForSuccessiveGcmReservations() {
        val random = DeterministicRandom()
        val first = random.generateGcmNonce()
        val second = random.generateGcmNonce()
        assertEquals(12, first.size)
        assertEquals(12, second.size)
        assertNotEquals(first.toList(), second.toList())
    }

    private class GuardedInputStream(private val bytes: ByteArray) : InputStream() {
        private var position = 0
        override fun read(): Int = if (position >= bytes.size) -1 else bytes[position++].toInt() and 0xff
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, 7_919, bytes.size - position)
            bytes.copyInto(target, offset, position, position + count)
            position += count
            return count
        }
        override fun readAllBytes(): ByteArray = error("stream consumer must not request whole-file materialization")
    }

    private class DeterministicRandom : SecureRandomSource {
        private var next = 1
        override fun generateBytes(size: Int): ByteArray = ByteArray(size) { (next++ and 0xff).toByte() }
    }
}
