package com.ashishkumar.nivara.domain.biometrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricThrottlePolicyTest {
    @Test
    fun firstFourFailuresAreNotDelayedAndFifthStartsThirtySecondBlock() {
        var state = BiometricAttemptState()
        repeat(4) { index ->
            state = BiometricThrottlePolicy.recordFailure(state, 10_000L + index)
            assertEquals(0L, BiometricThrottlePolicy.retryAfter(state, 10_000L + index))
        }
        state = BiometricThrottlePolicy.recordFailure(state, 20_000L)
        assertEquals(5, state.failedAttempts)
        assertEquals(30_000L, BiometricThrottlePolicy.retryAfter(state, 20_000L))
    }

    @Test
    fun afterBlockExpiresOneFailureStartsANewThirtySecondWindow() {
        var state = BiometricAttemptState(5, 50_000L)
        assertEquals(0L, BiometricThrottlePolicy.retryAfter(state, 50_000L))
        state = BiometricThrottlePolicy.recordFailure(state, 50_001L)
        assertEquals(5, state.failedAttempts)
        assertEquals(30_000L, BiometricThrottlePolicy.retryAfter(state, 50_001L))
    }

    @Test
    fun successfulPrimaryAuthenticationCanResetBiometricCounter() {
        val blocked = BiometricAttemptState(5, 60_000L)
        val reset = BiometricAttemptState()
        assertTrue(BiometricThrottlePolicy.retryAfter(blocked, 45_000L) > 0)
        assertEquals(0, reset.failedAttempts)
        assertEquals(0L, reset.blockedUntilEpochMillis)
    }
}
