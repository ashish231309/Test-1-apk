package com.ashishkumar.nivara.domain.security.session

import com.ashishkumar.nivara.domain.biometrics.BiometricAttemptState
import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticationResult
import com.ashishkumar.nivara.domain.credentials.AttemptState
import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialType
import com.ashishkumar.nivara.domain.security.TimeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSessionManagerTest {
    @Test
    fun newManagerStartsUnauthenticatedAndKeepsNoProcessSessionState() = sessionTest { harness ->
        assertEquals(SessionState.Unauthenticated, harness.manager.currentState())
        assertFalse(harness.manager.mayAccessSensitiveContent())

        assertTrue(harness.authenticatePrimary(primarySuccess()).sessionEstablished)
        assertTrue(harness.manager.mayAccessSensitiveContent())

        // A manager recreated with the same dependencies has no access to the prior in-memory session.
        val recreated = DefaultSessionManager(harness.time, harness.policy, harness.scope)
        assertEquals(SessionState.Unauthenticated, recreated.currentState())
        assertFalse(recreated.mayAccessSensitiveContent())
    }

    @Test
    fun successfulPrimaryAuthenticationCreatesPrimarySourcedSession() = sessionTest { harness ->
        val completion = harness.authenticatePrimary(primarySuccess())
        assertTrue(completion.sessionEstablished)
        val session = (harness.manager.currentState() as SessionState.Authenticated).session

        assertEquals(AuthenticationSource.PRIMARY, session.source)
        assertEquals(harness.time.elapsed, session.authenticatedAtElapsedRealtimeMillis)
        assertEquals(harness.time.elapsed + harness.policy.timeoutMillis, session.expiresAtElapsedRealtimeMillis)
        assertTrue(harness.manager.mayAccessSensitiveContent())
    }

    @Test
    fun successfulBiometricAuthenticationCreatesBiometricSourcedSession() = sessionTest { harness ->
        val completion = harness.authenticateBiometric(BiometricAuthenticationResult.Authenticated)
        assertTrue(completion.sessionEstablished)
        assertEquals(
            AuthenticationSource.BIOMETRIC,
            (harness.manager.currentState() as SessionState.Authenticated).session.source,
        )
        assertTrue(harness.manager.mayAccessSensitiveContent())
    }

    @Test
    fun failedAndNonterminalAuthenticationOutcomesCannotCreateASession() = sessionTest { harness ->
        val primaryOutcomes = listOf(
            AuthenticationResult.Failed,
            AuthenticationResult.NotConfigured,
            AuthenticationResult.InvalidConfiguration,
            AuthenticationResult.TemporarilyBlocked(1_000L),
        )
        primaryOutcomes.forEach { outcome ->
            assertFalse(harness.authenticatePrimary(outcome).sessionEstablished)
            assertEquals(SessionState.Unauthenticated, harness.manager.currentState())
        }
        val biometricOutcomes = listOf(
            BiometricAuthenticationResult.Failed,
            BiometricAuthenticationResult.UserCancelled,
            BiometricAuthenticationResult.SystemLockedOut,
            BiometricAuthenticationResult.Invalidated,
            BiometricAuthenticationResult.NotEnabled,
            BiometricAuthenticationResult.PersistenceFailure,
            BiometricAuthenticationResult.SystemError,
        )
        biometricOutcomes.forEach { outcome ->
            assertFalse(harness.authenticateBiometric(outcome).sessionEstablished)
            assertEquals(SessionState.Unauthenticated, harness.manager.currentState())
        }
        assertFalse(harness.manager.mayAccessSensitiveContent())
    }

    @Test
    fun sessionRemainsValidImmediatelyBeforeExpiryAndExpiresAtBoundary() = sessionTest { harness ->
        assertTrue(harness.authenticatePrimary(primarySuccess()).sessionEstablished)
        val session = (harness.manager.currentState() as SessionState.Authenticated).session

        harness.time.elapsed = session.expiresAtElapsedRealtimeMillis - 1
        assertEquals(SessionState.Authenticated(session), harness.manager.currentState())
        assertTrue(harness.manager.mayAccessSensitiveContent())

        harness.time.elapsed = session.expiresAtElapsedRealtimeMillis
        assertEquals(SessionState.Unauthenticated, harness.manager.currentState())
        assertFalse(harness.manager.mayAccessSensitiveContent())
    }

    @Test
    fun expiryCheckClearsSessionAndRepeatedChecksAreIdempotent() = sessionTest { harness ->
        assertTrue(harness.authenticatePrimary(primarySuccess()).sessionEstablished)
        val session = (harness.manager.currentState() as SessionState.Authenticated).session
        harness.time.elapsed = session.expiresAtElapsedRealtimeMillis + 50

        repeat(3) {
            assertEquals(SessionState.Unauthenticated, harness.manager.currentState())
            assertFalse(harness.manager.mayAccessSensitiveContent())
        }
        assertEquals(SessionState.Unauthenticated, harness.manager.sessionState.value)
    }

    @Test
    fun managerTimerEmitsUnauthenticatedWhenTheDeadlinePasses() = sessionTest(timeoutMillis = 30L) { harness ->
        assertTrue(harness.authenticatePrimary(primarySuccess()).sessionEstablished)
        val session = (harness.manager.currentState() as SessionState.Authenticated).session
        harness.time.elapsed = session.expiresAtElapsedRealtimeMillis

        withTimeout(1_000L) {
            harness.manager.sessionState.first { it == SessionState.Unauthenticated }
        }
        assertFalse(harness.manager.mayAccessSensitiveContent())
    }

    @Test
    fun timeoutIsAbsoluteConfiguredAndUnaffectedByWallClockChanges() = sessionTest(timeoutMillis = 60_000L) { harness ->
        assertTrue(harness.authenticatePrimary(primarySuccess()).sessionEstablished)
        val session = (harness.manager.currentState() as SessionState.Authenticated).session
        harness.time.epoch += 86_400_000L
        harness.time.elapsed += 10_000L

        assertEquals(SessionState.Authenticated(session), harness.manager.currentState())
        assertEquals(60_000L, session.expiresAtElapsedRealtimeMillis - session.authenticatedAtElapsedRealtimeMillis)
        assertEquals(
            session.expiresAtElapsedRealtimeMillis,
            (harness.manager.sessionState.value as SessionState.Authenticated).session.expiresAtElapsedRealtimeMillis,
        )
    }

    @Test
    fun quickLockImmediatelyClearsSessionAndRevokesSensitiveAccess() = sessionTest { harness ->
        assertTrue(harness.authenticatePrimary(primarySuccess()).sessionEstablished)
        assertTrue(harness.manager.mayAccessSensitiveContent())

        harness.manager.lockNow()
        assertEquals(SessionState.Unauthenticated, harness.manager.sessionState.value)
        assertFalse(harness.manager.mayAccessSensitiveContent())
    }

    @Test
    fun quickLockIsSafeAndIdempotentWhileUnauthenticatedOrRepeated() = sessionTest { harness ->
        repeat(3) {
            harness.manager.lockNow()
            assertEquals(SessionState.Unauthenticated, harness.manager.currentState())
        }
    }

    @Test
    fun quickLockInvalidatesAnAuthenticationAlreadyInProgress() = sessionTest { harness ->
        val started = CompletableDeferred<Unit>()
        val pendingOutcome = CompletableDeferred<BiometricAuthenticationResult>()
        val authentication = async {
            harness.manager.authenticateBiometric {
                started.complete(Unit)
                pendingOutcome.await()
            }
        }
        started.await()
        harness.manager.lockNow()
        pendingOutcome.complete(BiometricAuthenticationResult.Authenticated)

        assertFalse(authentication.await().sessionEstablished)
        assertEquals(SessionState.Unauthenticated, harness.manager.currentState())
        assertFalse(harness.manager.mayAccessSensitiveContent())
    }

    @Test
    fun authenticationAfterQuickLockMustCompleteFreshly() = sessionTest { harness ->
        harness.authenticateBiometric(BiometricAuthenticationResult.Authenticated)
        harness.manager.lockNow()
        assertFalse(harness.manager.mayAccessSensitiveContent())

        val completion = harness.authenticatePrimary(primarySuccess())
        assertTrue(completion.sessionEstablished)
        assertEquals(AuthenticationSource.PRIMARY, (harness.manager.currentState() as SessionState.Authenticated).session.source)
    }

    @Test
    fun newestConcurrentAuthenticationAttemptWinsAndOldOutcomeCannotReplaceIt() = sessionTest { harness ->
        val firstStarted = CompletableDeferred<Unit>()
        val firstOutcome = CompletableDeferred<AuthenticationResult>()
        val firstAuthentication = async {
            harness.manager.authenticatePrimary {
                firstStarted.complete(Unit)
                firstOutcome.await()
            }
        }
        firstStarted.await()

        val second = harness.authenticateBiometric(BiometricAuthenticationResult.Authenticated)
        assertTrue(second.sessionEstablished)
        assertEquals(AuthenticationSource.BIOMETRIC, (harness.manager.currentState() as SessionState.Authenticated).session.source)
        firstOutcome.complete(primarySuccess())
        val first = firstAuthentication.await()

        assertFalse(first.sessionEstablished)
        assertTrue(second.sessionEstablished)
        assertEquals(AuthenticationSource.BIOMETRIC, (harness.manager.currentState() as SessionState.Authenticated).session.source)
    }

    @Test
    fun failedReauthenticationDoesNotEraseAnExistingUnexpiredSession() = sessionTest { harness ->
        assertTrue(harness.authenticatePrimary(primarySuccess()).sessionEstablished)
        val failed = harness.authenticatePrimary(AuthenticationResult.Failed)
        assertFalse(failed.sessionEstablished)
        assertTrue(harness.manager.currentState() is SessionState.Authenticated)
        assertTrue(harness.manager.mayAccessSensitiveContent())
    }

    @Test
    fun sessionLockAndExpiryDoNotChangeEitherAuthenticationAttemptState() = sessionTest { harness ->
        val primaryAttempts = AttemptState(failedAttempts = 4, blockedUntilEpochMillis = 123_000L)
        val biometricAttempts = BiometricAttemptState(failedAttempts = 3, blockedUntilEpochMillis = 456_000L)
        val before = primaryAttempts to biometricAttempts

        assertTrue(harness.authenticatePrimary(primarySuccess()).sessionEstablished)
        harness.manager.lockNow()
        assertEquals(before, primaryAttempts to biometricAttempts)

        assertTrue(harness.authenticateBiometric(BiometricAuthenticationResult.Authenticated).sessionEstablished)
        val session = (harness.manager.currentState() as SessionState.Authenticated).session
        harness.time.elapsed = session.expiresAtElapsedRealtimeMillis
        harness.manager.currentState()
        assertEquals(before, primaryAttempts to biometricAttempts)
    }

    @Test
    fun primaryAndBiometricFailuresOnlyChangeTheirOwnAuthenticationThrottleState() = sessionTest { harness ->
        var primaryAttempts = AttemptState()
        var biometricAttempts = BiometricAttemptState()
        val primaryFailure = harness.manager.authenticatePrimary {
            primaryAttempts = primaryAttempts.copy(failedAttempts = primaryAttempts.failedAttempts + 1)
            AuthenticationResult.Failed
        }
        assertFalse(primaryFailure.sessionEstablished)
        assertEquals(SessionState.Unauthenticated, harness.manager.currentState())
        assertEquals(1, primaryAttempts.failedAttempts)
        assertEquals(BiometricAttemptState(), biometricAttempts)

        val biometricFailure = harness.manager.authenticateBiometric {
            biometricAttempts = biometricAttempts.copy(failedAttempts = biometricAttempts.failedAttempts + 1)
            BiometricAuthenticationResult.Failed
        }
        assertFalse(biometricFailure.sessionEstablished)
        assertEquals(SessionState.Unauthenticated, harness.manager.currentState())
        assertEquals(1, primaryAttempts.failedAttempts)
        assertEquals(1, biometricAttempts.failedAttempts)
    }

    @Test
    fun successfulReauthenticationReplacesSessionAndStoresOnlySourceAndTimes() = sessionTest { harness ->
        assertTrue(harness.authenticatePrimary(primarySuccess()).sessionEstablished)
        val first = (harness.manager.currentState() as SessionState.Authenticated).session
        harness.time.elapsed += 5_000L
        assertTrue(harness.authenticateBiometric(BiometricAuthenticationResult.Authenticated).sessionEstablished)
        val second = (harness.manager.currentState() as SessionState.Authenticated).session

        assertEquals(AuthenticationSource.BIOMETRIC, second.source)
        assertTrue(second.authenticatedAtElapsedRealtimeMillis > first.authenticatedAtElapsedRealtimeMillis)
        assertTrue(second.expiresAtElapsedRealtimeMillis > first.expiresAtElapsedRealtimeMillis)
        assertEquals(
            setOf("source", "authenticatedAtElapsedRealtimeMillis", "expiresAtElapsedRealtimeMillis"),
            second.javaClass.declaredFields.filterNot { it.isSynthetic || it.name.startsWith("\$") }
                .map { it.name }.toSet(),
        )
    }

    private class MutableTimeProvider(
        var epoch: Long = 1_000_000L,
        var elapsed: Long = 10_000L,
    ) : TimeProvider {
        override fun nowEpochMillis(): Long = epoch
        override fun nowElapsedRealtimeMillis(): Long = elapsed
    }

    private class Harness(timeoutMillis: Long) {
        val time = MutableTimeProvider()
        val policy = SessionTimeoutPolicy(timeoutMillis)
        private val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val manager = DefaultSessionManager(time, policy, scope)

        suspend fun authenticatePrimary(outcome: AuthenticationResult) =
            manager.authenticatePrimary { outcome }

        suspend fun authenticateBiometric(outcome: BiometricAuthenticationResult) =
            manager.authenticateBiometric { outcome }

        fun close() = scope.cancel()
    }

    private fun sessionTest(
        timeoutMillis: Long = SessionTimeoutPolicy.DEFAULT_TIMEOUT_MILLIS,
        body: suspend CoroutineScope.(Harness) -> Unit,
    ) = runBlocking {
        val harness = Harness(timeoutMillis)
        try {
            body(harness)
        } finally {
            harness.close()
        }
    }

    private fun primarySuccess() = AuthenticationResult.Authenticated(PrimaryCredentialType.PASSWORD)
}
