package com.ashishkumar.nivara.data.credentials

import android.os.SystemClock
import com.ashishkumar.nivara.domain.security.TimeProvider

/** Shared system clock: wall time for persisted throttling, monotonic elapsed realtime for memory-only sessions. */
class SystemCredentialClock : TimeProvider {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()

    override fun nowElapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}
