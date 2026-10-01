package com.ashishkumar.nivara.data.security

import com.ashishkumar.nivara.domain.security.SecurityFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class JcaSecureRandomSourceTest {
    private val random = JcaSecureRandomSource()

    @Test
    fun generatesExpectedSizesAndDoesNotRepeatValuesInOrdinaryRuns() {
        val keys = List(8) { random.generateAes256KeyBytes() }
        val nonces = List(8) { random.generateGcmNonce() }
        val salts = List(8) { random.generateSalt() }
        val recoveryKeys = List(8) { random.generateRecoveryKeyBytes() }
        try {
            assertEquals(setOf(32), keys.map { it.size }.toSet())
            assertEquals(setOf(12), nonces.map { it.size }.toSet())
            assertEquals(setOf(16), salts.map { it.size }.toSet())
            assertEquals(setOf(32), recoveryKeys.map { it.size }.toSet())
            assertPairwiseDistinct(keys)
            assertPairwiseDistinct(nonces)
            assertPairwiseDistinct(salts)
            assertPairwiseDistinct(recoveryKeys)
        } finally {
            (keys + recoveryKeys + salts + nonces).forEach { it.fill(0) }
        }
    }

    @Test
    fun rejectsInvalidSizes() {
        assertThrows(SecurityFailure.InvalidParameters::class.java) { random.generateBytes(0) }
        assertThrows(SecurityFailure.InvalidParameters::class.java) { random.generateBytes(-1) }
    }

    private fun assertPairwiseDistinct(values: List<ByteArray>) {
        for (left in values.indices) {
            for (right in left + 1 until values.size) {
                assertFalse(values[left].contentEquals(values[right]))
            }
        }
    }
}
