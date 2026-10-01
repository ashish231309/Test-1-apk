package com.ashishkumar.nivara.domain.applock

import com.ashishkumar.nivara.domain.permissions.UsageAccessRepository
import com.ashishkumar.nivara.domain.permissions.UsageAccessStatus
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Centralized cadence so the service and tests use one documented polling policy. */
data class AppLockMonitoringPolicy(
    val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    val degradedRetryMillis: Long = DEFAULT_DEGRADED_RETRY_MILLIS,
) {
    init {
        require(pollIntervalMillis >= 1_000L)
        require(degradedRetryMillis >= pollIntervalMillis)
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS = 2_000L
        const val DEFAULT_DEGRADED_RETRY_MILLIS = 10_000L
    }
}

/** Platform-independent coordinator. It never displays UI or creates an authentication/session authority. */
class DefaultAppLockMonitor(
    private val usageAccessRepository: UsageAccessRepository,
    private val protectedApplicationRepository: ProtectedApplicationRepository,
    private val foregroundDetector: ForegroundApplicationDetector,
    private val sessionManager: SessionManager,
    private val nivaraPackageName: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val policy: AppLockMonitoringPolicy = AppLockMonitoringPolicy(),
) : AppLockMonitor {
    private val mutableState = MutableStateFlow<AppLockDetectionState>(AppLockDetectionState.Stopped)
    override val state = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<ProtectionEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    override val protectionEvents = mutableEvents.asSharedFlow()

    private val pollMutex = Mutex()
    private val eventDebouncer = AuthenticationRequestDebouncer()
    private val lifecycleLock = Any()
    private var monitoringJob: Job? = null

    init {
        require(nivaraPackageName.isNotBlank())
    }

    override fun start(): Boolean = synchronized(lifecycleLock) {
        if (monitoringJob?.isActive == true) return@synchronized false
        foregroundDetector.reset()
        eventDebouncer.reset()
        mutableState.value = AppLockDetectionState.Starting
        monitoringJob = scope.launch {
            while (isActive) {
                pollOnce()
                val waitMillis = when (mutableState.value) {
                    is AppLockDetectionState.Degraded,
                    AppLockDetectionState.NoProtectedApplications -> policy.degradedRetryMillis
                    else -> policy.pollIntervalMillis
                }
                delay(waitMillis)
            }
        }
        true
    }

    override suspend fun reportUnavailable(reason: AppLockDegradedReason) {
        pollMutex.withLock { degrade(reason) }
    }

    override suspend fun reportNoProtectedApplications() {
        pollMutex.withLock {
            eventDebouncer.reset()
            foregroundDetector.reset()
            mutableState.value = AppLockDetectionState.NoProtectedApplications
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            monitoringJob?.cancel()
            monitoringJob = null
            foregroundDetector.reset()
            eventDebouncer.reset()
            mutableState.value = AppLockDetectionState.Stopped
        }
    }

    /** One deterministic observation step, also used directly by JVM tests. */
    suspend fun pollOnce() = pollMutex.withLock {
        val usageStatus = try {
            usageAccessRepository.status()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            degrade(AppLockDegradedReason.USAGE_ACCESS_UNAVAILABLE)
            return@withLock
        }

        when (usageStatus) {
            UsageAccessStatus.GRANTED -> Unit
            UsageAccessStatus.NOT_GRANTED -> {
                degrade(AppLockDegradedReason.USAGE_ACCESS_NOT_GRANTED)
                return@withLock
            }
            UsageAccessStatus.UNAVAILABLE -> {
                degrade(AppLockDegradedReason.USAGE_ACCESS_UNAVAILABLE)
                return@withLock
            }
        }

        val protectedSnapshot = try {
            protectedApplicationRepository.getProtectedApplications()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            degrade(AppLockDegradedReason.PROTECTED_APPLICATIONS_UNAVAILABLE)
            return@withLock
        }
        val protectedApplications = when (protectedSnapshot) {
            is ProtectedApplicationsSnapshot.Available -> protectedSnapshot.applications
            ProtectedApplicationsSnapshot.Unavailable -> {
                degrade(AppLockDegradedReason.PROTECTED_APPLICATIONS_UNAVAILABLE)
                return@withLock
            }
        }
        if (protectedApplications.isEmpty()) {
            eventDebouncer.reset()
            mutableState.value = AppLockDetectionState.NoProtectedApplications
            return@withLock
        }

        val detection = try {
            foregroundDetector.detect()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            degrade(AppLockDegradedReason.FOREGROUND_DETECTION_UNAVAILABLE)
            return@withLock
        }
        if (detection is ForegroundDetectionResult.Unavailable) {
            degrade(
                AppLockDegradedReason.FOREGROUND_DETECTION_UNAVAILABLE,
                resetDetector = detection.reason != ForegroundUnavailableReason.NO_USABLE_FOREGROUND_EVENT,
            )
            return@withLock
        }
        val foreground = (detection as? ForegroundDetectionResult.Foreground)?.application

        val currentSession = try {
            // This is the sole authority for expiry, timeout, and Quick Lock state.
            sessionManager.currentState()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            degrade(AppLockDegradedReason.SESSION_STATE_UNAVAILABLE)
            return@withLock
        }
        val decision = ProtectionDecider.decide(
            foreground = foreground,
            protectedApplications = protectedApplications,
            sessionState = currentSession,
            nivaraPackageName = nivaraPackageName,
        )
        val event = eventDebouncer.eventFor(decision)
        if (event != null) mutableEvents.tryEmit(event)
        mutableState.value = AppLockDetectionState.Monitoring(
            foregroundPackage = foreground?.packageName,
            decision = decision,
            authenticationRequestPendingFor = eventDebouncer.pendingPackage,
        )
    }

    private fun degrade(
        reason: AppLockDegradedReason,
        resetDetector: Boolean = true,
    ) {
        eventDebouncer.reset()
        if (resetDetector) foregroundDetector.reset()
        mutableState.value = AppLockDetectionState.Degraded(reason)
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 8
    }
}
