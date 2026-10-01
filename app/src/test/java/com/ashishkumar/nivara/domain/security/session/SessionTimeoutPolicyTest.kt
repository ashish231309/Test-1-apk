package com.ashishkumar.nivara.domain.security.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTimeoutPolicyTest {
    @Test
    fun defaultAndConfiguredTimeoutsAreDomainValues() {
        assertEquals(5 * 60 * 1_000L, SessionTimeoutPolicy.DEFAULT.timeoutMillis)
        val configured = SessionTimeoutPolicy(90_000L)
        assertEquals(90_000L, configured.expiresAt(10_000L) - 10_000L)
    }

    @Test
    fun sessionIsValidBeforeAndExpiredAtTheExactDeadline() {
        val policy = SessionTimeoutPolicy(60_000L)
        val expiry = policy.expiresAt(25_000L)
        assertFalse(policy.isExpired(expiry - 1, expiry))
        assertTrue(policy.isExpired(expiry, expiry))
        assertTrue(policy.isExpired(expiry + 1, expiry))
    }

    @Test
    fun remainingTimeIsClampedAtZero() {
        val policy = SessionTimeoutPolicy(10_000L)
        assertEquals(5_000L, policy.remainingMillis(15_000L, 20_000L))
        assertEquals(0L, policy.remainingMillis(20_000L, 20_000L))
        assertEquals(0L, policy.remainingMillis(21_000L, 20_000L))
    }

    @Test
    fun deadlineSaturatesInsteadOfOverflowing() {
        val policy = SessionTimeoutPolicy(2_000L)
        assertEquals(Long.MAX_VALUE, policy.expiresAt(Long.MAX_VALUE - 1_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveTimeout() {
        SessionTimeoutPolicy(0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedTimeoutConfiguration() {
        SessionTimeoutPolicy(SessionTimeoutPolicy.MAX_TIMEOUT_MILLIS + 1)
    }
}
