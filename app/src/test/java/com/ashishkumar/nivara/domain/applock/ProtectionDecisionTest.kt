package com.ashishkumar.nivara.domain.applock

import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialType
import com.ashishkumar.nivara.domain.security.TimeProvider
import com.ashishkumar.nivara.domain.security.session.DefaultSessionManager
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import com.ashishkumar.nivara.domain.security.session.SessionTimeoutPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionDecisionTest {
    private val protected = setOf(ProtectedApplication("com.example.private"))
    private val foreground = ForegroundApplication("com.example.private")

    @Test
    fun noForegroundAndNivaraForegroundNeverRequireAuthentication() {
        assertEquals(
            ProtectionDecision.NoProtectionRequired,
            ProtectionDecider.decide(null, protected, SessionState.Unauthenticated, NivaraPackage),
        )
        assertEquals(
            ProtectionDecision.NoProtectionRequired,
            ProtectionDecider.decide(
                ForegroundApplication(NivaraPackage),
                protected + ProtectedApplication(NivaraPackage),
                SessionState.Unauthenticated,
                NivaraPackage,
            ),
        )
    }

    @Test
    fun unprotectedForegroundDoesNotRequireAuthentication() {
        assertEquals(
            ProtectionDecision.NoProtectionRequired,
            ProtectionDecider.decide(
                ForegroundApplication("com.example.public"),
                protected,
                SessionState.Unauthenticated,
                NivaraPackage,
            ),
        )
    }

    @Test
    fun protectedForegroundRequiresTheExistingSessionAuthorityToBeAuthenticated() {
        assertEquals(
            ProtectionDecision.AuthenticationRequired(foreground.packageName),
            ProtectionDecider.decide(foreground, protected, SessionState.Unauthenticated, NivaraPackage),
        )
        assertEquals(
            ProtectionDecision.NoProtectionRequired,
            ProtectionDecider.decide(
                foreground,
                protected,
                SessionState.Authenticated(
                    com.ashishkumar.nivara.domain.security.session.AuthenticatedSession(
                        source = com.ashishkumar.nivara.domain.security.session.AuthenticationSource.PRIMARY,
                        authenticatedAtElapsedRealtimeMillis = 10,
                        expiresAtElapsedRealtimeMillis = 20,
                    ),
                ),
                NivaraPackage,
            ),
        )
    }

    @Test
    fun expiredSessionAndQuickLockAreObservedFromSessionManagerOnTheNextDecision() = runBlocking {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val time = MutableElapsedTime()
        val manager = DefaultSessionManager(time, SessionTimeoutPolicy(timeoutMillis = 100), applicationScope)
        val monitor = monitorFor(manager)
        val events = mutableListOf<ProtectionEvent>()
        val eventCollector = applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            monitor.protectionEvents.collect { events += it }
        }
        try {
            assertTrue(manager.authenticatePrimary {
                AuthenticationResult.Authenticated(PrimaryCredentialType.PIN)
            }.sessionEstablished)
            monitor.pollOnce()
            assertEquals(
                ProtectionDecision.NoProtectionRequired,
                (monitor.state.value as AppLockDetectionState.Monitoring).decision,
            )

            manager.lockNow()
            monitor.pollOnce()
            assertEquals(
                ProtectionDecision.AuthenticationRequired(foreground.packageName),
                (monitor.state.value as AppLockDetectionState.Monitoring).decision,
            )
            monitor.pollOnce()
            assertEquals(1, events.size) // Repeated polls do not repeat the pending request.

            manager.authenticatePrimary {
                AuthenticationResult.Authenticated(PrimaryCredentialType.PIN)
            }
            monitor.pollOnce() // Observing the manager-authorized session clears the pending request.
            assertEquals(ProtectionDecision.NoProtectionRequired, (monitor.state.value as AppLockDetectionState.Monitoring).decision)

            time.elapsed = 100
            monitor.pollOnce() // The manager's absolute expiry restores the protection decision.
            assertEquals(
                ProtectionDecision.AuthenticationRequired(foreground.packageName),
                (monitor.state.value as AppLockDetectionState.Monitoring).decision,
            )
            assertEquals(2, events.size)

            manager.authenticatePrimary {
                AuthenticationResult.Authenticated(PrimaryCredentialType.PIN)
            }
            monitor.pollOnce()
            manager.lockNow()
            monitor.pollOnce() // Quick Lock is likewise read from the one session manager.
            assertEquals(ProtectionDecision.AuthenticationRequired(foreground.packageName), (monitor.state.value as AppLockDetectionState.Monitoring).decision)
            assertEquals(3, events.size)
        } finally {
            eventCollector.cancel()
            monitor.stop()
            applicationScope.cancel()
        }
    }

    @Test
    fun eventDebouncerEmitsOnceUntilTheProtectedAppIsLeftOrSessionBecomesValid() {
        val debouncer = AuthenticationRequestDebouncer()
        val required = ProtectionDecision.AuthenticationRequired(foreground.packageName)

        assertEquals(ProtectionEvent.AuthenticationRequired(foreground.packageName), debouncer.eventFor(required))
        assertEquals(null, debouncer.eventFor(required))
        assertEquals(null, debouncer.eventFor(ProtectionDecision.NoProtectionRequired))
        assertEquals(ProtectionEvent.AuthenticationRequired(foreground.packageName), debouncer.eventFor(required))
    }

    private fun monitorFor(manager: SessionManager) = DefaultAppLockMonitor(
        usageAccessRepository = FakeUsageAccessRepository(),
        protectedApplicationRepository = FakeProtectedApplicationRepository(
            ProtectedApplicationsSnapshot.Available(protected),
        ),
        foregroundDetector = FakeForegroundDetector(ForegroundDetectionResult.Foreground(foreground)),
        sessionManager = manager,
        nivaraPackageName = NivaraPackage,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private class MutableElapsedTime(var elapsed: Long = 0) : TimeProvider {
        override fun nowEpochMillis(): Long = elapsed
        override fun nowElapsedRealtimeMillis(): Long = elapsed
    }

    private class FakeUsageAccessRepository : com.ashishkumar.nivara.domain.permissions.UsageAccessRepository {
        override suspend fun status() = com.ashishkumar.nivara.domain.permissions.UsageAccessStatus.GRANTED
        override fun openSettings() = com.ashishkumar.nivara.domain.permissions.UsageAccessSettingsResult.OPENED
    }

    private class FakeProtectedApplicationRepository(
        private val snapshot: ProtectedApplicationsSnapshot,
    ) : ProtectedApplicationRepository {
        override suspend fun getProtectedApplications() = snapshot
        override suspend fun isProtected(packageName: String) = ProtectedApplicationLookup.Unavailable
        override suspend fun setProtected(
            application: ProtectedApplication,
            protected: Boolean,
        ) = ProtectedApplicationUpdateResult.UNAVAILABLE
    }

    private class FakeForegroundDetector(
        private val result: ForegroundDetectionResult,
    ) : ForegroundApplicationDetector {
        override suspend fun detect() = result
        override fun reset() = Unit
    }

    private companion object {
        const val NivaraPackage = "com.example.nivara"
    }
}
