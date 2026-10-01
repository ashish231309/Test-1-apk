package com.ashishkumar.nivara.domain.applock

import com.ashishkumar.nivara.domain.permissions.UsageAccessRepository
import com.ashishkumar.nivara.domain.permissions.UsageAccessSettingsResult
import com.ashishkumar.nivara.domain.permissions.UsageAccessStatus
import com.ashishkumar.nivara.domain.security.TimeProvider
import com.ashishkumar.nivara.domain.security.session.DefaultSessionManager
import com.ashishkumar.nivara.domain.security.session.SessionTimeoutPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAppLockMonitorTest {
    @Test
    fun notGrantedAndUnavailableAreExplicitDegradedStatesNeverAnEmptyAppOrSafeDecision() = runBlocking {
        listOf(
            UsageAccessStatus.NOT_GRANTED to AppLockDegradedReason.USAGE_ACCESS_NOT_GRANTED,
            UsageAccessStatus.UNAVAILABLE to AppLockDegradedReason.USAGE_ACCESS_UNAVAILABLE,
        ).forEach { (status, expectedReason) ->
            val fixture = Fixture(usageStatus = status, snapshot = ProtectedApplicationsSnapshot.Available(emptySet()))
            try {
                fixture.monitor.pollOnce()
                assertEquals(AppLockDetectionState.Degraded(expectedReason), fixture.monitor.state.value)
                assertFalse(fixture.monitor.state.value is AppLockDetectionState.NoProtectedApplications)
                assertEquals(0, fixture.detector.calls)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun anEmptyProtectedSetIsOnlyReportedAfterUsageAccessWasConfirmedGranted() = runBlocking {
        val fixture = Fixture(
            usageStatus = UsageAccessStatus.GRANTED,
            snapshot = ProtectedApplicationsSnapshot.Available(emptySet()),
        )
        try {
            fixture.monitor.pollOnce()
            assertEquals(AppLockDetectionState.NoProtectedApplications, fixture.monitor.state.value)
            assertEquals(0, fixture.detector.calls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun corruptProtectedConfigurationAndMissingForegroundEventsAreDegradedNotSafe() = runBlocking {
        val invalidStore = Fixture(UsageAccessStatus.GRANTED, ProtectedApplicationsSnapshot.Unavailable)
        try {
            invalidStore.monitor.pollOnce()
            assertEquals(
                AppLockDetectionState.Degraded(AppLockDegradedReason.PROTECTED_APPLICATIONS_UNAVAILABLE),
                invalidStore.monitor.state.value,
            )
        } finally {
            invalidStore.close()
        }

        val invalidDetector = Fixture(
            usageStatus = UsageAccessStatus.GRANTED,
            snapshot = ProtectedApplicationsSnapshot.Available(setOf(ProtectedApplication(ProtectedPackage))),
            detection = ForegroundDetectionResult.Unavailable(ForegroundUnavailableReason.NO_USABLE_FOREGROUND_EVENT),
        )
        try {
            invalidDetector.monitor.pollOnce()
            assertEquals(
                AppLockDetectionState.Degraded(AppLockDegradedReason.FOREGROUND_DETECTION_UNAVAILABLE),
                invalidDetector.monitor.state.value,
            )
        } finally {
            invalidDetector.close()
        }
    }

    @Test
    fun currentSessionStateIsReadOnEveryDecisionInsteadOfBeingCached() = runBlocking {
        val fixture = Fixture(
            usageStatus = UsageAccessStatus.GRANTED,
            snapshot = ProtectedApplicationsSnapshot.Available(setOf(ProtectedApplication(ProtectedPackage))),
            detection = ForegroundDetectionResult.Foreground(ForegroundApplication(ProtectedPackage)),
        )
        try {
            fixture.monitor.pollOnce()
            val first = fixture.monitor.state.value as AppLockDetectionState.Monitoring
            assertEquals(ProtectionDecision.AuthenticationRequired(ProtectedPackage), first.decision)

            fixture.monitor.pollOnce()
            val second = fixture.monitor.state.value as AppLockDetectionState.Monitoring
            assertEquals(first.authenticationRequestPendingFor, second.authenticationRequestPendingFor)
            assertEquals(first.observationSequence + 1, second.observationSequence)
            assertEquals(2, fixture.sessionManagerReads)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun startingTwiceDoesNotCreateTwoMonitoringLoopsAndStopIsDeterministic() {
        val fixture = Fixture(
            usageStatus = UsageAccessStatus.GRANTED,
            snapshot = ProtectedApplicationsSnapshot.Available(emptySet()),
        )
        try {
            assertTrue(fixture.monitor.start())
            assertFalse(fixture.monitor.start())
            fixture.monitor.stop()
            assertEquals(AppLockDetectionState.Stopped, fixture.monitor.state.value)
            assertTrue(fixture.detector.wasReset)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        usageStatus: UsageAccessStatus,
        snapshot: ProtectedApplicationsSnapshot,
        detection: ForegroundDetectionResult = ForegroundDetectionResult.Unavailable(
            ForegroundUnavailableReason.NO_USABLE_FOREGROUND_EVENT,
        ),
    ) {
        private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val underlyingSessionManager =
            DefaultSessionManager(TimeProvider { 0L }, SessionTimeoutPolicy.DEFAULT, sessionScope)
        var sessionManagerReads = 0
            private set
        private val sessionManager = object : com.ashishkumar.nivara.domain.security.session.SessionManager by
            underlyingSessionManager {
            override suspend fun currentState(): com.ashishkumar.nivara.domain.security.session.SessionState {
                sessionManagerReads++
                return underlyingSessionManager.currentState()
            }
        }
        val detector = CountingDetector(detection)
        val monitor = DefaultAppLockMonitor(
            usageAccessRepository = FixedUsageAccessRepository(usageStatus),
            protectedApplicationRepository = FixedProtectedRepository(snapshot),
            foregroundDetector = detector,
            sessionManager = sessionManager,
            nivaraPackageName = NivaraPackage,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        fun close() {
            monitor.stop()
            sessionScope.cancel()
        }
    }

    private class FixedUsageAccessRepository(private val value: UsageAccessStatus) : UsageAccessRepository {
        override suspend fun status() = value
        override fun openSettings() = UsageAccessSettingsResult.UNAVAILABLE
    }

    private class FixedProtectedRepository(
        private val value: ProtectedApplicationsSnapshot,
    ) : ProtectedApplicationRepository {
        override suspend fun getProtectedApplications() = value
        override suspend fun isProtected(packageName: String) = ProtectedApplicationLookup.Unavailable
        override suspend fun setProtected(
            application: ProtectedApplication,
            protected: Boolean,
        ) = ProtectedApplicationUpdateResult.UNAVAILABLE
    }

    private class CountingDetector(private val value: ForegroundDetectionResult) : ForegroundApplicationDetector {
        var calls = 0
        var wasReset = false
        override suspend fun detect(): ForegroundDetectionResult {
            calls++
            return value
        }
        override fun reset() {
            wasReset = true
        }
    }

    private companion object {
        const val NivaraPackage = "com.example.nivara"
        const val ProtectedPackage = "com.example.protected"
    }
}
