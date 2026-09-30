package com.ashishkumar.nivara.data.security

import com.ashishkumar.nivara.domain.security.KdfParameters
import com.ashishkumar.nivara.domain.security.SecurityFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class JcaCredentialKeyDeriverTest {
    private val deriver = JcaCredentialKeyDeriver(JcaSecureRandomSource())

    @Test
    fun samePasswordSaltAndParametersAreDeterministic() = runBlocking {
        val parameters = KdfParameters.DEFAULT
        val password = charArrayOf('t', 'e', 's', 't')
        val salt = ByteArray(parameters.saltBytes) { (it + 1).toByte() }
        val otherSalt = salt.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val first = deriver.deriveKey(password, salt, parameters)
        val repeat = deriver.deriveKey(password, salt, parameters)
        val changedSalt = deriver.deriveKey(password, otherSalt, parameters)
        try {
            assertEquals(parameters.outputBytes, first.size)
            assertArrayEquals(first, repeat)
            assertFalse(first.contentEquals(changedSalt))
        } finally {
            password.fill('\u0000')
            salt.fill(0)
            otherSalt.fill(0)
            first.fill(0)
            repeat.fill(0)
            changedSalt.fill(0)
        }
    }

    @Test
    fun saltGenerationAndConfigurationAreVersioned() {
        val salt = deriver.newSalt()
        try {
            assertEquals(KdfParameters.DEFAULT_SALT_BYTES, salt.size)
            assertEquals(1, KdfParameters.DEFAULT.version)
            assertEquals(600_000, KdfParameters.DEFAULT.iterations)
            assertEquals(32, KdfParameters.DEFAULT.outputBytes)
        } finally {
            salt.fill(0)
        }
    }

    @Test
    fun unsupportedOrWeakParametersAndInvalidSaltAreRejected() {
        assertThrows(SecurityFailure.UnsupportedKdfVersion::class.java) {
            KdfParameters(version = 2)
        }
        assertThrows(SecurityFailure.InvalidParameters::class.java) {
            KdfParameters(iterations = KdfParameters.MIN_ITERATIONS - 1)
        }
        assertThrows(SecurityFailure.InvalidParameters::class.java) {
            KdfParameters(saltBytes = 8)
        }
        assertThrows(SecurityFailure.InvalidParameters::class.java) {
            runBlocking { deriver.deriveKey(charArrayOf('x'), ByteArray(8), KdfParameters.DEFAULT) }
        }
    }
}
