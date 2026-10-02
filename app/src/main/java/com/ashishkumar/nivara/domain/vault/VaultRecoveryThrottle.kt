package com.ashishkumar.nivara.domain.vault

import com.ashishkumar.nivara.domain.security.TimeProvider

/** Process-memory-only bounded retry delay for failed recovery-envelope authentication. */
class VaultRecoveryThrottle(private val timeProvider: TimeProvider) {
    private var consecutiveAuthenticationFailures = 0
    private var blockedUntilElapsedMillis = 0L

    fun retryAfterMillis(): Long = (blockedUntilElapsedMillis - timeProvider.nowElapsedRealtimeMillis()).coerceAtLeast(0L)

    fun recordAuthenticationFailure(): Long {
        consecutiveAuthenticationFailures =
            (consecutiveAuthenticationFailures + 1).coerceAtMost(MAX_TRACKED_FAILURES)
        val exponent = (consecutiveAuthenticationFailures - 1).coerceAtMost(MAX_BACKOFF_EXPONENT)
        val delay = (BASE_DELAY_MILLIS shl exponent).coerceAtMost(MAX_DELAY_MILLIS)
        val now = timeProvider.nowElapsedRealtimeMillis()
        blockedUntilElapsedMillis = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
        return delay
    }

    fun reset() {
        consecutiveAuthenticationFailures = 0
        blockedUntilElapsedMillis = 0L
    }

    private companion object {
        const val BASE_DELAY_MILLIS = 1_000L
        const val MAX_DELAY_MILLIS = 30_000L
        const val MAX_TRACKED_FAILURES = 16
        const val MAX_BACKOFF_EXPONENT = 5
    }
}
