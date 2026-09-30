package com.ashishkumar.nivara.domain.credentials

import com.ashishkumar.nivara.data.security.AesGcmKeyWrappingService
import com.ashishkumar.nivara.data.security.JcaAesGcmEncryption
import com.ashishkumar.nivara.data.security.JcaCredentialKeyDeriver
import com.ashishkumar.nivara.data.security.JcaSecureRandomSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialServiceTest {
    @Test
    fun exactlyOnePrimaryTypeCanBeEnrolledAndStatusReportsSelection() = runBlocking {
        val store = InMemoryCredentialStore()
        val service = service(store)
        assertEquals(CredentialServiceStatus.NotConfigured, service.status())

        val enrollment = service.enroll(PrimaryCredentialType.PIN, chars("482951"), chars("482951"))
        assertEquals(EnrollmentResult.Enrolled(PrimaryCredentialType.PIN), enrollment)
        assertEquals(CredentialServiceStatus.Configured(PrimaryCredentialType.PIN), service.status())
        assertEquals(
            EnrollmentResult.AlreadyConfigured,
            service.enroll(PrimaryCredentialType.PASSWORD, chars("Long enough test phrase"), chars("Long enough test phrase")),
        )
        assertEquals(PrimaryCredentialType.PIN, store.load()?.type)
    }

    @Test
    fun pinEnrollmentValidatesStrengthConfirmationAndClearsInputArrays() = runBlocking {
        val service = service(InMemoryCredentialStore())
        assertEquals(
            EnrollmentResult.Rejected(CredentialRejection.TOO_SHORT),
            service.enroll(PrimaryCredentialType.PIN, charArrayOf(), charArrayOf()),
        )
        assertEquals(
            EnrollmentResult.Rejected(CredentialRejection.TOO_SHORT),
            service.enroll(PrimaryCredentialType.PIN, chars("1234"), chars("1234")),
        )
        assertEquals(
            EnrollmentResult.Rejected(CredentialRejection.TOO_WEAK),
            service.enroll(PrimaryCredentialType.PIN, chars("123456"), chars("123456")),
        )
        val pin = chars("482951")
        val confirmation = chars("482952")
        assertEquals(
            EnrollmentResult.ConfirmationMismatch,
            service.enroll(PrimaryCredentialType.PIN, pin, confirmation),
        )
        assertArrayEquals(CharArray(pin.size), pin)
        assertArrayEquals(CharArray(confirmation.size), confirmation)
    }

    @Test
    fun pinAuthenticatesOnlyMatchingCredentialAndChangeReplacesIt() = runBlocking {
        val service = service(InMemoryCredentialStore())
        service.enroll(PrimaryCredentialType.PIN, chars("482951"), chars("482951"))
        assertEquals(AuthenticationResult.Authenticated(PrimaryCredentialType.PIN), service.authenticate(chars("482951")))
        assertEquals(AuthenticationResult.Failed, service.authenticate(chars("482950")))
        assertEquals(
            CredentialChangeResult.Changed(PrimaryCredentialType.PASSWORD),
            service.changePrimary(
                currentCredential = chars("482951"),
                newType = PrimaryCredentialType.PASSWORD,
                newCredential = chars("New secure example phrase"),
                confirmation = chars("New secure example phrase"),
            ),
        )
        assertEquals(AuthenticationResult.Failed, service.authenticate(chars("482951")))
        assertEquals(
            AuthenticationResult.Authenticated(PrimaryCredentialType.PASSWORD),
            service.authenticate(chars("New secure example phrase")),
        )
        assertEquals(CredentialServiceStatus.Configured(PrimaryCredentialType.PASSWORD), service.status())
    }

    @Test
    fun passwordEnrollmentAndVerificationUseTheSameRealKdfPath() = runBlocking {
        val service = service(InMemoryCredentialStore())
        assertEquals(
            EnrollmentResult.Rejected(CredentialRejection.TOO_SHORT),
            service.enroll(PrimaryCredentialType.PASSWORD, charArrayOf(), charArrayOf()),
        )
        assertEquals(
            EnrollmentResult.Rejected(CredentialRejection.TOO_SHORT),
            service.enroll(PrimaryCredentialType.PASSWORD, chars("short"), chars("short")),
        )
        assertEquals(
            EnrollmentResult.ConfirmationMismatch,
            service.enroll(
                PrimaryCredentialType.PASSWORD,
                chars("A proper test passphrase"),
                chars("A different passphrase"),
            ),
        )
        assertEquals(
            EnrollmentResult.Enrolled(PrimaryCredentialType.PASSWORD),
            service.enroll(
                PrimaryCredentialType.PASSWORD,
                chars("A proper test passphrase"),
                chars("A proper test passphrase"),
            ),
        )
        assertEquals(AuthenticationResult.Failed, service.authenticate(chars("Not the test passphrase")))
        assertEquals(
            AuthenticationResult.Authenticated(PrimaryCredentialType.PASSWORD),
            service.authenticate(chars("A proper test passphrase")),
        )
        assertEquals(
            CredentialChangeResult.Changed(PrimaryCredentialType.PIN),
            service.changePrimary(
                chars("A proper test passphrase"),
                PrimaryCredentialType.PIN,
                chars("627194"),
                chars("627194"),
            ),
        )
        assertEquals(AuthenticationResult.Failed, service.authenticate(chars("A proper test passphrase")))
        assertEquals(AuthenticationResult.Authenticated(PrimaryCredentialType.PIN), service.authenticate(chars("627194")))
    }

    @Test
    fun patternCanonicalizationRequiresUniqueConnectedCellsAndVerifies() = runBlocking {
        val service = service(InMemoryCredentialStore())
        val shortFailure = assertThrows(InvalidCredentialInput::class.java) {
            PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 2))
        }
        assertEquals(CredentialRejection.PATTERN_TOO_SHORT, shortFailure.reason)
        assertThrows(InvalidCredentialInput::class.java) {
            PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 1, 2))
        }
        val first = PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 4, 7))
        val confirmation = PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 4, 6))
        assertEquals(
            EnrollmentResult.ConfirmationMismatch,
            service.enroll(PrimaryCredentialType.PATTERN, first, confirmation),
        )

        val pattern = PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 4, 7))
        val samePattern = PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 4, 7))
        assertEquals(EnrollmentResult.Enrolled(PrimaryCredentialType.PATTERN),
            service.enroll(PrimaryCredentialType.PATTERN, pattern, samePattern))
        assertEquals(
            AuthenticationResult.Authenticated(PrimaryCredentialType.PATTERN),
            service.authenticate(PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 4, 7))),
        )
        assertEquals(
            AuthenticationResult.Failed,
            service.authenticate(PatternCanonicalizer.canonicalize(intArrayOf(0, 2, 4, 7))),
        )
        assertEquals(
            CredentialChangeResult.Changed(PrimaryCredentialType.PIN),
            service.changePrimary(
                PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 4, 7)),
                PrimaryCredentialType.PIN,
                chars("627194"),
                chars("627194"),
            ),
        )
        assertEquals(
            AuthenticationResult.Failed,
            service.authenticate(PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 4, 7))),
        )
        assertEquals(AuthenticationResult.Authenticated(PrimaryCredentialType.PIN), service.authenticate(chars("627194")))
    }

    @Test
    fun patternCanonicalFormatIsVersionedOrderSensitiveAndNotAVisibleSequence() {
        val canonical = PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 4, 7))
        val reordered = PatternCanonicalizer.canonicalize(intArrayOf(0, 4, 1, 7))
        val skippedMidpoints = PatternCanonicalizer.canonicalize(intArrayOf(0, 2, 8, 6))
        val explicitMidpoints = PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 2, 5, 8, 7, 6))
        try {
            assertTrue(PatternCanonicalizer.validate(canonical) == null)
            assertFalse(canonical.contentEquals(reordered))
            assertTrue(skippedMidpoints.contentEquals(explicitMidpoints))
            assertTrue(canonical.all { it in '0'..'9' || it in 'a'..'f' })
        } finally {
            canonical.fill('\u0000')
            reordered.fill('\u0000')
            skippedMidpoints.fill('\u0000')
            explicitMidpoints.fill('\u0000')
        }
    }

    @Test
    fun changingRequiresTheCurrentCredentialAndMismatchesDoNotReplaceIt() = runBlocking {
        val service = service(InMemoryCredentialStore())
        service.enroll(PrimaryCredentialType.PIN, chars("482951"), chars("482951"))
        assertEquals(
            CredentialChangeResult.AuthenticationFailed,
            service.changePrimary(
                chars("111111"), PrimaryCredentialType.PASSWORD,
                chars("Another secure phrase"), chars("Another secure phrase"),
            ),
        )
        assertEquals(
            CredentialChangeResult.ConfirmationMismatch,
            service.changePrimary(
                chars("482951"), PrimaryCredentialType.PASSWORD,
                chars("Another secure phrase"), chars("Yet another secure phrase"),
            ),
        )
        assertEquals(CredentialServiceStatus.Configured(PrimaryCredentialType.PIN), service.status())
        assertEquals(AuthenticationResult.Authenticated(PrimaryCredentialType.PIN), service.authenticate(chars("482951")))
    }

    @Test
    fun failedAttemptsThrottleTemporarilyAndSuccessResetsTracking() = runBlocking {
        val store = InMemoryCredentialStore()
        val clock = TestCredentialClock()
        val service = service(store, clock)
        service.enroll(PrimaryCredentialType.PASSWORD, chars("A secure test phrase"), chars("A secure test phrase"))

        repeat(3) {
            assertEquals(AuthenticationResult.Failed, service.authenticate(chars("Wrong secure phrase")))
        }
        val blocked = service.authenticate(chars("Wrong secure phrase"))
        assertEquals(AuthenticationResult.TemporarilyBlocked(1_000), blocked)
        assertEquals(4, store.attempts().failedAttempts)
        assertEquals(AuthenticationResult.TemporarilyBlocked(1_000), service.authenticate(chars("A secure test phrase")))

        clock.now += 1_000
        assertEquals(
            AuthenticationResult.Authenticated(PrimaryCredentialType.PASSWORD),
            service.authenticate(chars("A secure test phrase")),
        )
        assertEquals(0, store.attempts().failedAttempts)
        assertEquals(0, store.attempts().blockedUntilEpochMillis)
    }

    @Test
    fun noCredentialAndInvalidSubmissionReturnTypedBoundaries() = runBlocking {
        val service = service(InMemoryCredentialStore())
        assertEquals(AuthenticationResult.NotConfigured, service.authenticate(chars("whatever")))
        assertEquals(
            listOf(PrimaryCredentialType.PIN, PrimaryCredentialType.PASSWORD, PrimaryCredentialType.PATTERN),
            PrimaryCredentialType.entries,
        )
    }

    private fun service(
        store: InMemoryCredentialStore,
        clock: TestCredentialClock = TestCredentialClock(),
    ): DefaultPrimaryCredentialService {
        val random = JcaSecureRandomSource()
        return DefaultPrimaryCredentialService(
            store = store,
            keyDeriver = JcaCredentialKeyDeriver(random),
            keyWrapping = AesGcmKeyWrappingService(JcaAesGcmEncryption(random)),
            random = random,
            clock = clock,
        )
    }

    private fun chars(value: String): CharArray = value.toCharArray()

    private class TestCredentialClock(var now: Long = 100_000L) : CredentialClock {
        override fun nowEpochMillis(): Long = now
    }

    private class InMemoryCredentialStore : PrimaryCredentialStore {
        private var credential: StoredPrimaryCredential? = null
        private var attemptState = AttemptState()

        override suspend fun load(): StoredPrimaryCredential? = credential

        override suspend fun installIfAbsent(credential: StoredPrimaryCredential): Boolean {
            if (this.credential != null) return false
            this.credential = credential
            attemptState = AttemptState()
            return true
        }

        override suspend fun replace(credential: StoredPrimaryCredential) {
            this.credential = credential
            attemptState = AttemptState()
        }

        override suspend fun attempts(): AttemptState = attemptState

        override suspend fun recordFailure(nowEpochMillis: Long): AttemptState {
            val count = if (attemptState.failedAttempts == Int.MAX_VALUE) Int.MAX_VALUE
            else attemptState.failedAttempts + 1
            val delay = AttemptThrottlePolicy.delayForFailure(count)
            val blockedUntil = if (delay == 0L) 0 else nowEpochMillis + delay
            return AttemptState(count, blockedUntil).also { attemptState = it }
        }

        override suspend fun resetAttempts() {
            attemptState = AttemptState()
        }
    }
}
