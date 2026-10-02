package com.ashishkumar.nivara.domain.vault

import com.ashishkumar.nivara.domain.security.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class VaultRecoveryThrottleTest {
    @Test
    fun exponentialDelayIsBoundedAndResetReturnsToTheInitialDelay() {
        val clock = FakeClock()
        val throttle = VaultRecoveryThrottle(clock)
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)

        expected.forEach { delay ->
            assertEquals(delay, throttle.recordAuthenticationFailure())
            assertEquals(delay, throttle.retryAfterMillis())
            clock.elapsedMillis += delay
            assertEquals(0L, throttle.retryAfterMillis())
        }

        throttle.reset()
        assertEquals(0L, throttle.retryAfterMillis())
        assertEquals(1_000L, throttle.recordAuthenticationFailure())
    }

    private class FakeClock(var elapsedMillis: Long = 5_000L) : TimeProvider {
        override fun nowEpochMillis(): Long = elapsedMillis
        override fun nowElapsedRealtimeMillis(): Long = elapsedMillis
    }
}
