package com.ashishkumar.nivara.domain.credentials

/** Exponential, temporary online throttling only; this policy never creates a permanent lockout. */
object AttemptThrottlePolicy {
    const val FIRST_DELAYED_FAILURE = 4
    const val MAX_DELAY_MILLIS = 30_000L

    fun delayForFailure(failedAttempts: Int): Long {
        if (failedAttempts < FIRST_DELAYED_FAILURE) return 0
        val additionalFailures = (failedAttempts - FIRST_DELAYED_FAILURE).coerceAtMost(5)
        var delay = 1_000L
        repeat(additionalFailures) { delay = (delay * 2).coerceAtMost(MAX_DELAY_MILLIS) }
        return delay
    }

    fun retryAfter(state: AttemptState, nowEpochMillis: Long): Long =
        (state.blockedUntilEpochMillis - nowEpochMillis).coerceAtLeast(0)
}
