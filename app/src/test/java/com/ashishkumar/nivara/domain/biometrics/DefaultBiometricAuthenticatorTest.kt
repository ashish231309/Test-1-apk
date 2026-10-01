package com.ashishkumar.nivara.domain.biometrics

import com.ashishkumar.nivara.domain.security.TimeProvider

import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.credentials.CredentialChangeResult
import com.ashishkumar.nivara.domain.credentials.CredentialServiceStatus
import com.ashishkumar.nivara.domain.credentials.EnrollmentResult
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialService
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultBiometricAuthenticatorTest {
    @Test
    fun availabilityReportsNoHardwareEnrollmentTemporaryAndUnsupportedStates() = runBlocking {
        val harness = Harness()
        assertEquals(
            BiometricStatus.Disabled(BiometricAvailability.AVAILABLE),
            harness.authenticator.status(),
        )
        harness.store.setState(BiometricRecordState.ENABLED)
        assertEquals(BiometricStatus.Enabled, harness.authenticator.status())
        harness.store.setState(BiometricRecordState.DISABLED)

        val cases = listOf(
            BiometricAvailability.NO_HARDWARE to BiometricUnavailableReason.NO_HARDWARE,
            BiometricAvailability.HARDWARE_UNAVAILABLE to BiometricUnavailableReason.HARDWARE_UNAVAILABLE,
            BiometricAvailability.NO_ENROLLED_BIOMETRICS to BiometricUnavailableReason.NO_ENROLLED_BIOMETRICS,
            BiometricAvailability.UNSUPPORTED to BiometricUnavailableReason.UNSUPPORTED,
            BiometricAvailability.SECURITY_UPDATE_REQUIRED to BiometricUnavailableReason.SECURITY_UPDATE_REQUIRED,
            BiometricAvailability.UNKNOWN to BiometricUnavailableReason.UNKNOWN,
        )
        cases.forEach { (availability, reason) ->
            harness.platform.currentAvailability = availability
            assertEquals(BiometricStatus.Disabled(availability), harness.authenticator.status())
            harness.store.setState(BiometricRecordState.ENABLED)
            assertEquals(BiometricStatus.Unavailable(reason), harness.authenticator.status())
            harness.store.setState(BiometricRecordState.DISABLED)
        }
    }

    @Test
    fun successfulBiometricAuthenticationResetsOnlyBiometricAttempts() = runBlocking {
        val harness = Harness()
        harness.store.setState(BiometricRecordState.ENABLED)
        repeat(3) { harness.store.recordFailure(harness.clock.now) }
        assertEquals(
            BiometricAuthenticationResult.Authenticated,
            harness.authenticator.authenticate(),
        )
        assertEquals(BiometricAttemptState(), harness.store.attempts())
        assertEquals(0, harness.primary.failedAttempts)
        assertTrue(harness.platform.lastCreateKey.not())
    }

    @Test
    fun fifthFailedMatchCancelsPromptAndStartsAppThirtySecondBlock() = runBlocking {
        val harness = Harness()
        harness.store.setState(BiometricRecordState.ENABLED)
        harness.platform.failedMatches = 5
        assertEquals(
            BiometricAuthenticationResult.TemporarilyBlocked(30_000L),
            harness.authenticator.authenticate(),
        )
        assertEquals(5, harness.store.attempts().failedAttempts)
        assertEquals(30_000L, BiometricThrottlePolicy.retryAfter(harness.store.attempts(), harness.clock.now))
        assertEquals(0, harness.primary.failedAttempts)
    }

    @Test
    fun appBlockExpiresAndTheNextFailedBiometricAttemptReblocksForThirtySeconds() = runBlocking {
        val harness = Harness()
        harness.store.setState(BiometricRecordState.ENABLED)
        repeat(5) { harness.store.recordFailure(harness.clock.now) }
        harness.clock.now += 30_000L
        harness.platform.failedMatches = 1
        harness.platform.terminal = BiometricPlatformResult.UserCancelled
        assertEquals(
            BiometricAuthenticationResult.TemporarilyBlocked(30_000L),
            harness.authenticator.authenticate(),
        )
        assertEquals(30_000L, BiometricThrottlePolicy.retryAfter(harness.store.attempts(), harness.clock.now))
    }

    @Test
    fun systemLockoutIsMappedSeparatelyAndDoesNotIncrementAppOrPrimaryFailures() = runBlocking {
        val harness = Harness()
        harness.store.setState(BiometricRecordState.ENABLED)
        repeat(2) { harness.store.recordFailure(harness.clock.now) }
        harness.platform.terminal = BiometricPlatformResult.SystemLockedOut
        assertEquals(BiometricAuthenticationResult.SystemLockedOut, harness.authenticator.authenticate())
        assertEquals(2, harness.store.attempts().failedAttempts)
        assertEquals(0, harness.primary.failedAttempts)
    }

    @Test
    fun userCancellationAndPrimaryFallbackKeepAttemptCountersSeparate() = runBlocking {
        val harness = Harness()
        harness.store.setState(BiometricRecordState.ENABLED)
        harness.platform.failedMatches = 2
        harness.platform.terminal = BiometricPlatformResult.UserCancelled
        assertEquals(BiometricAuthenticationResult.UserCancelled, harness.authenticator.authenticate())
        assertEquals(2, harness.store.attempts().failedAttempts)
        assertEquals(0, harness.primary.failedAttempts)

        assertEquals(AuthenticationResult.Failed, harness.authenticator.authenticatePrimaryFallback("wrong".toCharArray()))
        assertEquals(1, harness.primary.failedAttempts)
        assertEquals(2, harness.store.attempts().failedAttempts)

        assertEquals(
            AuthenticationResult.Authenticated(PrimaryCredentialType.PIN),
            harness.authenticator.authenticatePrimaryFallback("valid-primary".toCharArray()),
        )
        assertEquals(BiometricAttemptState(), harness.store.attempts())
    }

    @Test
    fun enableRequiresConfiguredPrimaryAuthenticationAndDisableLeavesPrimaryIntact() = runBlocking {
        val harness = Harness()
        assertEquals(
            BiometricManagementResult.PrimaryAuthenticationFailed,
            harness.authenticator.enable("wrong".toCharArray()),
        )
        assertFalse(harness.platform.lastCreateKey)
        assertEquals(BiometricRecordState.DISABLED, harness.store.recordState())

        harness.primary.configured = false
        assertEquals(
            BiometricManagementResult.PrimaryCredentialRequired,
            harness.authenticator.enable("valid-primary".toCharArray()),
        )
        assertFalse(harness.platform.lastCreateKey)
        harness.primary.configured = true

        assertEquals(BiometricManagementResult.Enabled, harness.authenticator.enable("valid-primary".toCharArray()))
        assertEquals(BiometricRecordState.ENABLED, harness.store.recordState())
        assertTrue(harness.platform.keyExists)

        assertEquals(
            BiometricManagementResult.PrimaryAuthenticationFailed,
            harness.authenticator.disable("wrong".toCharArray()),
        )
        assertEquals(BiometricRecordState.ENABLED, harness.store.recordState())
        assertTrue(harness.platform.keyExists)

        assertEquals(BiometricManagementResult.Disabled, harness.authenticator.disable("valid-primary".toCharArray()))
        assertEquals(BiometricRecordState.DISABLED, harness.store.recordState())
        assertFalse(harness.platform.keyExists)
        assertEquals(CredentialServiceStatus.Configured(PrimaryCredentialType.PIN), harness.primary.status())
    }

    @Test
    fun cancellingEnrollmentDoesNotEnableBiometricsOrLeaveAUsableKey() = runBlocking {
        val harness = Harness()
        harness.platform.terminal = BiometricPlatformResult.UserCancelled
        assertEquals(
            BiometricManagementResult.UserCancelled,
            harness.authenticator.enable("valid-primary".toCharArray()),
        )
        assertEquals(BiometricRecordState.DISABLED, harness.store.recordState())
        assertFalse(harness.platform.keyExists)
    }

    @Test
    fun primaryFallbackRequestAndSystemErrorsRemainTyped() = runBlocking {
        val harness = Harness()
        harness.store.setState(BiometricRecordState.ENABLED)
        harness.platform.terminal = BiometricPlatformResult.PrimaryCredentialRequired
        assertEquals(
            BiometricAuthenticationResult.PrimaryCredentialRequired,
            harness.authenticator.authenticate(),
        )
        harness.platform.terminal = BiometricPlatformResult.SystemError
        assertEquals(BiometricAuthenticationResult.SystemError, harness.authenticator.authenticate())
        assertEquals(0, harness.primary.failedAttempts)
    }

    @Test
    fun invalidatedKeyFallsBackWithoutChangingThePrimaryCredentialAndCanBeExplicitlyReenabled() = runBlocking {
        val harness = Harness()
        harness.store.setState(BiometricRecordState.ENABLED)
        harness.platform.terminal = BiometricPlatformResult.Invalidated
        assertEquals(BiometricAuthenticationResult.Invalidated, harness.authenticator.authenticate())
        assertEquals(BiometricRecordState.INVALIDATED, harness.store.recordState())
        assertEquals(0, harness.primary.failedAttempts)
        assertEquals(
            AuthenticationResult.Authenticated(PrimaryCredentialType.PIN),
            harness.authenticator.authenticatePrimaryFallback("valid-primary".toCharArray()),
        )
        assertEquals(BiometricRecordState.INVALIDATED, harness.store.recordState())
        harness.platform.terminal = BiometricPlatformResult.Authenticated
        assertEquals(BiometricManagementResult.Enabled, harness.authenticator.enable("valid-primary".toCharArray()))
        assertEquals(BiometricRecordState.ENABLED, harness.store.recordState())
    }

    @Test
    fun noPrimaryCredentialPreventsBiometricAuthenticationEvenWhenKeyIsMarkedEnabled() = runBlocking {
        val harness = Harness()
        harness.store.setState(BiometricRecordState.ENABLED)
        harness.primary.configured = false
        assertEquals(
            BiometricAuthenticationResult.Unavailable(BiometricUnavailableReason.PRIMARY_CREDENTIAL_REQUIRED),
            harness.authenticator.authenticate(),
        )
        assertEquals(0, harness.platform.authenticationCalls)
    }

    private class Harness {
        val clock = MutableClock()
        val primary = FakePrimaryService()
        val platform = FakePlatform()
        val store = FakeBiometricStateStore()
        val authenticator = DefaultBiometricAuthenticator(primary, platform, store, clock)
    }

    private class MutableClock(var now: Long = 100_000L) : TimeProvider {
        override fun nowEpochMillis(): Long = now
    }

    private class FakePlatform : BiometricPromptPlatform {
        var currentAvailability = BiometricAvailability.AVAILABLE
        var terminal: BiometricPlatformResult = BiometricPlatformResult.Authenticated
        var failedMatches = 0
        var authenticationCalls = 0
        var lastCreateKey = false
        var keyExists = false

        override fun availability(): BiometricAvailability = currentAvailability

        override suspend fun authenticate(
            createKey: Boolean,
            continueAfterFailure: suspend () -> Boolean,
        ): BiometricPlatformResult {
            authenticationCalls++
            lastCreateKey = createKey
            if (createKey) keyExists = true
            repeat(failedMatches) {
                if (!continueAfterFailure()) {
                    if (createKey) keyExists = false
                    return BiometricPlatformResult.ApplicationThrottled
                }
            }
            if (createKey && terminal != BiometricPlatformResult.Authenticated) keyExists = false
            return terminal
        }

        override suspend fun deleteKey() {
            keyExists = false
        }
    }

    private class FakeBiometricStateStore : BiometricStateStore {
        private var state = BiometricRecordState.DISABLED
        private var attemptState = BiometricAttemptState()

        override suspend fun recordState(): BiometricRecordState = state
        override suspend fun setState(state: BiometricRecordState) { this.state = state }
        override suspend fun attempts(): BiometricAttemptState = attemptState
        override suspend fun recordFailure(nowEpochMillis: Long): BiometricAttemptState =
            BiometricThrottlePolicy.recordFailure(attemptState, nowEpochMillis).also { attemptState = it }
        override suspend fun resetAttempts() { attemptState = BiometricAttemptState() }
    }

    private class FakePrimaryService : PrimaryCredentialService {
        var configured = true
        var failedAttempts = 0
        private val expected = "valid-primary".toCharArray()

        override suspend fun status(): CredentialServiceStatus = if (configured) {
            CredentialServiceStatus.Configured(PrimaryCredentialType.PIN)
        } else {
            CredentialServiceStatus.NotConfigured
        }

        override suspend fun enroll(
            type: PrimaryCredentialType,
            credential: CharArray,
            confirmation: CharArray,
        ): EnrollmentResult = error("Not used in this test")

        override suspend fun authenticate(credential: CharArray): AuthenticationResult {
            if (!configured) {
                credential.fill('\u0000')
                return AuthenticationResult.NotConfigured
            }
            val matches = credential.contentEquals(expected)
            credential.fill('\u0000')
            return if (matches) AuthenticationResult.Authenticated(PrimaryCredentialType.PIN)
            else {
                failedAttempts++
                AuthenticationResult.Failed
            }
        }

        override suspend fun changePrimary(
            currentCredential: CharArray,
            newType: PrimaryCredentialType,
            newCredential: CharArray,
            confirmation: CharArray,
        ): CredentialChangeResult = error("Not used in this test")
    }
}
