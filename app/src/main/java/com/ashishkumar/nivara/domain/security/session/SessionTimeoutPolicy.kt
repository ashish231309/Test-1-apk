package com.ashishkumar.nivara.domain.security.session

/** Pure absolute-session timeout policy, independent of credential and biometric attempt throttles. */
data class SessionTimeoutPolicy(val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
    init {
        require(timeoutMillis in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS)
    }

    fun expiresAt(startedAtElapsedRealtimeMillis: Long): Long {
        require(startedAtElapsedRealtimeMillis >= 0)
        return if (startedAtElapsedRealtimeMillis > Long.MAX_VALUE - timeoutMillis) {
            Long.MAX_VALUE
        } else {
            startedAtElapsedRealtimeMillis + timeoutMillis
        }
    }

    /** Expiry is inclusive: a session is invalid at the precise deadline. */
    fun isExpired(nowElapsedRealtimeMillis: Long, expiresAtElapsedRealtimeMillis: Long): Boolean =
        nowElapsedRealtimeMillis >= expiresAtElapsedRealtimeMillis

    fun remainingMillis(nowElapsedRealtimeMillis: Long, expiresAtElapsedRealtimeMillis: Long): Long =
        (expiresAtElapsedRealtimeMillis - nowElapsedRealtimeMillis.coerceAtLeast(0)).coerceAtLeast(0)

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        const val MIN_TIMEOUT_MILLIS = 1L
        const val MAX_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L

        val DEFAULT = SessionTimeoutPolicy()
    }
}
