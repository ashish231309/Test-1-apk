package com.ashishkumar.nivara.domain.security

/**
 * Shared clock boundary. Epoch time is for persisted attempt deadlines; elapsed realtime is monotonic and is
 * for in-memory session expiry. Platform implementations should override both clocks with their appropriate
 * sources. The default also lets simple JVM tests inject a deterministic lambda; session tests override both clocks.
 */
fun interface TimeProvider {
    fun nowEpochMillis(): Long

    /** Monotonic milliseconds since an arbitrary origin. Production Android code uses elapsed realtime. */
    fun nowElapsedRealtimeMillis(): Long = nowEpochMillis()
}
