package com.ashishkumar.nivara.domain.biometrics

/** App-local throttling; Android's biometric/system lockout remains independent and authoritative. */
object BiometricThrottlePolicy {
    const val MAX_FAILED_ATTEMPTS = 5
    const val BLOCK_DURATION_MILLIS = 30_000L

    fun recordFailure(state: BiometricAttemptState, nowEpochMillis: Long): BiometricAttemptState {
        val count = if (state.failedAttempts >= MAX_FAILED_ATTEMPTS) MAX_FAILED_ATTEMPTS
        else state.failedAttempts + 1
        val blockedUntil = if (count < MAX_FAILED_ATTEMPTS) {
            0L
        } else if (nowEpochMillis > Long.MAX_VALUE - BLOCK_DURATION_MILLIS) {
            Long.MAX_VALUE
        } else {
            nowEpochMillis.coerceAtLeast(0) + BLOCK_DURATION_MILLIS
        }
        return BiometricAttemptState(count, blockedUntil)
    }

    fun retryAfter(state: BiometricAttemptState, nowEpochMillis: Long): Long =
        (state.blockedUntilEpochMillis - nowEpochMillis.coerceAtLeast(0)).coerceAtLeast(0)
}
