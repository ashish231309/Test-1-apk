package com.ashishkumar.nivara.domain.applock

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface AppLockDetectionState {
    data object Stopped : AppLockDetectionState
    data object Starting : AppLockDetectionState
    data object NoProtectedApplications : AppLockDetectionState

    data class Monitoring(
        val foregroundPackage: String?,
        val decision: ProtectionDecision,
        /** Forces presentation consumers to re-check non-UsageEvents prerequisites on each monitor cadence. */
        val observationSequence: Long = 0,
        /** A debouncing marker for Stage 8 presentation; this is not an authenticated/unlocked state. */
        val authenticationRequestPendingFor: String?,
    ) : AppLockDetectionState

    data class Degraded(val reason: AppLockDegradedReason) : AppLockDetectionState
}

enum class AppLockDegradedReason {
    USAGE_ACCESS_NOT_GRANTED,
    USAGE_ACCESS_UNAVAILABLE,
    OVERLAY_PERMISSION_NOT_GRANTED,
    OVERLAY_PERMISSION_UNAVAILABLE,
    PROTECTED_APPLICATIONS_UNAVAILABLE,
    FOREGROUND_DETECTION_UNAVAILABLE,
    SESSION_STATE_UNAVAILABLE,
    SERVICE_START_UNAVAILABLE,
}

/** One process-scoped coordinator; Android Service owns when start/stop are called. */
interface AppLockMonitor {
    val state: StateFlow<AppLockDetectionState>
    val protectionEvents: SharedFlow<ProtectionEvent>

    /** Idempotent. Returns true only when a new monitoring loop was created. */
    fun start(): Boolean

    /** Requests one serialized, immediate observation before a security-sensitive overlay transition. */
    suspend fun refreshNow()

    /** Exposes a prerequisite/platform failure even when monitoring could not be started. */
    suspend fun reportUnavailable(reason: AppLockDegradedReason)

    /** Reports a successful prerequisite check with no configured protected packages. */
    suspend fun reportNoProtectedApplications()

    /** Cancels the loop, releases transient detector/debounce state, and emits Stopped. */
    fun stop()
}

enum class MonitoringStartResult {
    START_REQUESTED,
    USAGE_ACCESS_NOT_GRANTED,
    USAGE_ACCESS_UNAVAILABLE,
    OVERLAY_PERMISSION_NOT_GRANTED,
    OVERLAY_PERMISSION_UNAVAILABLE,
    PROTECTED_APPLICATIONS_UNAVAILABLE,
    NO_PROTECTED_APPLICATIONS,
    START_UNAVAILABLE,
}

interface AppLockMonitoringController {
    /** Must be called from a user-visible app context on Android 12+; the foreground service is user-visible. */
    suspend fun start(): MonitoringStartResult

    fun stop()
}
