package com.ashishkumar.nivara.domain.applock

import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticationResult
import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.credentials.CredentialChangeResult
import com.ashishkumar.nivara.domain.credentials.CredentialServiceStatus
import com.ashishkumar.nivara.domain.credentials.EnrollmentResult
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialService
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialType
import com.ashishkumar.nivara.domain.security.TimeProvider
import com.ashishkumar.nivara.domain.security.session.AuthenticationSource
import com.ashishkumar.nivara.domain.security.session.DefaultSessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import com.ashishkumar.nivara.domain.security.session.SessionTimeoutPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockAuthenticationRouterTest {
    @Test
    fun primarySuccessUsesExistingServiceAndCreatesNormalPrimarySession() = withFixture { fixture ->
        val credential = "abc123".toCharArray()
        val result = fixture.router.authenticatePrimary(credential) { true }

        assertTrue(result is AppLockAuthenticationRouteResult.Completed)
        assertEquals(1, fixture.primary.calls)
        assertArrayEquals(CharArray(6), credential)
        val state = fixture.sessionManager.currentState() as SessionState.Authenticated
        assertEquals(AuthenticationSource.PRIMARY, state.session.source)
    }

    @Test
    fun primaryFailureUsesExistingServiceButDoesNotCreateSession() = withFixture { fixture ->
        fixture.primary.result = AuthenticationResult.Failed
        val result = fixture.router.authenticatePrimary("secret".toCharArray()) { true }

        assertTrue(result is AppLockAuthenticationRouteResult.Completed)
        assertEquals(SessionState.Unauthenticated, fixture.sessionManager.currentState())
    }

    @Test
    fun stalePrimarySuccessIsCancelledBeforeSessionManagerCanCommit() = withFixture { fixture ->
        val result = fixture.router.authenticatePrimary("secret".toCharArray()) { false }

        assertEquals(AppLockAuthenticationRouteResult.RequestInvalidated, result)
        assertEquals(SessionState.Unauthenticated, fixture.sessionManager.currentState())
    }

    @Test
    fun biometricSuccessUsesExistingAuthenticatorAndCreatesNormalBiometricSession() = withFixture { fixture ->
        val result = fixture.router.authenticateBiometric(
            authenticate = { BiometricAuthenticationResult.Authenticated },
            requestStillValid = { true },
        )

        assertTrue(result is AppLockAuthenticationRouteResult.Completed)
        val state = fixture.sessionManager.currentState() as SessionState.Authenticated
        assertEquals(AuthenticationSource.BIOMETRIC, state.session.source)
    }

    @Test
    fun biometricFailureAndCancellationDoNotCreateSessions() = withFixture { fixture ->
        listOf(BiometricAuthenticationResult.Failed, BiometricAuthenticationResult.UserCancelled).forEach { outcome ->
            val result = fixture.router.authenticateBiometric({ outcome }) { true }
            assertTrue(result is AppLockAuthenticationRouteResult.Completed)
            assertEquals(SessionState.Unauthenticated, fixture.sessionManager.currentState())
        }
    }

    @Test
    fun staleBiometricSuccessIsCancelledBeforeSessionManagerCanCommit() = withFixture { fixture ->
        val result = fixture.router.authenticateBiometric(
            authenticate = { BiometricAuthenticationResult.Authenticated },
            requestStillValid = { false },
        )

        assertEquals(AppLockAuthenticationRouteResult.RequestInvalidated, result)
        assertEquals(SessionState.Unauthenticated, fixture.sessionManager.currentState())
    }

    @Test
    fun quickLockDuringPrimaryAuthenticationCannotRestoreTheSession() = withFixture { fixture ->
        coroutineScope {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.primary.beforeReturn = {
                started.complete(Unit)
                release.await()
            }
            val attempt = async {
                fixture.router.authenticatePrimary("secret".toCharArray()) { true }
            }

            started.await()
            fixture.sessionManager.lockNow()
            release.complete(Unit)
            val result = attempt.await() as AppLockAuthenticationRouteResult.Completed<AuthenticationResult>

            assertTrue(result.completion.outcome is AuthenticationResult.Authenticated)
            assertEquals(false, result.completion.sessionEstablished)
            assertEquals(SessionState.Unauthenticated, fixture.sessionManager.currentState())
        }
    }

    @Test
    fun existingSessionSkipsPrimaryAndBiometricAuthentication() = withFixture { fixture ->
        fixture.sessionManager.authenticatePrimary { AuthenticationResult.Authenticated(PrimaryCredentialType.PIN) }
        var biometricCalls = 0

        val primary = fixture.router.authenticatePrimary("secret".toCharArray()) { true }
        val biometric = fixture.router.authenticateBiometric(
            authenticate = { biometricCalls++; BiometricAuthenticationResult.Authenticated },
            requestStillValid = { true },
        )

        assertEquals(AppLockAuthenticationRouteResult.ExistingSession, primary)
        assertEquals(AppLockAuthenticationRouteResult.ExistingSession, biometric)
        assertEquals(0, fixture.primary.calls)
        assertEquals(0, biometricCalls)
    }

    private fun withFixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = DefaultSessionManager(TimeProvider { 1L }, SessionTimeoutPolicy(timeoutMillis = 100), scope)
        val primary = FakePrimaryCredentialService()
        try {
            block(Fixture(AppLockAuthenticationRouter(primary, manager), manager, primary))
        } finally {
            scope.cancel()
        }
    }

    private data class Fixture(
        val router: AppLockAuthenticationRouter,
        val sessionManager: DefaultSessionManager,
        val primary: FakePrimaryCredentialService,
    )

    private class FakePrimaryCredentialService : PrimaryCredentialService {
        var calls = 0
        var beforeReturn: suspend () -> Unit = {}
        var result: AuthenticationResult = AuthenticationResult.Authenticated(PrimaryCredentialType.PASSWORD)

        override suspend fun status() = CredentialServiceStatus.Configured(PrimaryCredentialType.PASSWORD)
        override suspend fun enroll(
            type: PrimaryCredentialType,
            credential: CharArray,
            confirmation: CharArray,
        ) = EnrollmentResult.AlreadyConfigured

        override suspend fun authenticate(credential: CharArray): AuthenticationResult {
            calls++
            beforeReturn()
            return result
        }

        override suspend fun changePrimary(
            currentCredential: CharArray,
            newType: PrimaryCredentialType,
            newCredential: CharArray,
            confirmation: CharArray,
        ) = CredentialChangeResult.InvalidConfiguration
    }
}
