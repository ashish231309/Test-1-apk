package com.ashishkumar.nivara.domain.security.session

import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticationResult
import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.security.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-memory-only session gate. A session uses an absolute timeout from authentication; normal use does
 * not extend it. A mutex serializes auth completion, timeout and Quick Lock. Internal attempt generations stop
 * an authentication already in flight when Quick Lock is invoked from re-authorizing the app afterward.
 */
class DefaultSessionManager(
    private val timeProvider: TimeProvider,
    val timeoutPolicy: SessionTimeoutPolicy = SessionTimeoutPolicy.DEFAULT,
    private val applicationScope: CoroutineScope,
) : SessionManager {
    private val mutex = Mutex()
    private val mutableSessionState = MutableStateFlow<SessionState>(SessionState.Unauthenticated)
    override val sessionState: StateFlow<SessionState> = mutableSessionState.asStateFlow()

    private var authenticationGeneration = 0L
    private var sessionGeneration = 0L
    private var expiryJob: Job? = null

    override suspend fun authenticatePrimary(
        authenticate: suspend () -> AuthenticationResult,
    ): SessionAuthenticationCompletion<AuthenticationResult> = authenticateAndEstablish(
        source = AuthenticationSource.PRIMARY,
        authenticate = authenticate,
        succeeded = { it is AuthenticationResult.Authenticated },
    )

    override suspend fun authenticateBiometric(
        authenticate: suspend () -> BiometricAuthenticationResult,
    ): SessionAuthenticationCompletion<BiometricAuthenticationResult> = authenticateAndEstablish(
        source = AuthenticationSource.BIOMETRIC,
        authenticate = authenticate,
        succeeded = { it == BiometricAuthenticationResult.Authenticated },
    )

    override suspend fun currentState(): SessionState = mutex.withLock {
        expireCurrentIfNecessaryLocked()
        mutableSessionState.value
    }

    override suspend fun mayAccessSensitiveContent(): Boolean =
        currentState() is SessionState.Authenticated

    override suspend fun lockNow() {
        mutex.withLock {
            authenticationGeneration = next(authenticationGeneration)
            clearSessionLocked(cancelExpiry = true)
        }
    }

    private suspend fun <T> authenticateAndEstablish(
        source: AuthenticationSource,
        authenticate: suspend () -> T,
        succeeded: (T) -> Boolean,
    ): SessionAuthenticationCompletion<T> {
        val attemptGeneration = mutex.withLock {
            expireCurrentIfNecessaryLocked()
            authenticationGeneration = next(authenticationGeneration)
            authenticationGeneration
        }
        val outcome = authenticate()
        var sessionEstablished = false
        mutex.withLock {
            expireCurrentIfNecessaryLocked()
            if (attemptGeneration != authenticationGeneration) return@withLock

            // Consume each attempt exactly once, including failures, so an old result cannot be replayed.
            authenticationGeneration = next(authenticationGeneration)
            if (!succeeded(outcome)) return@withLock

            val startedAt = timeProvider.nowElapsedRealtimeMillis().coerceAtLeast(0)
            val session = AuthenticatedSession(
                source = source,
                authenticatedAtElapsedRealtimeMillis = startedAt,
                expiresAtElapsedRealtimeMillis = timeoutPolicy.expiresAt(startedAt),
            )
            sessionGeneration = next(sessionGeneration)
            expiryJob?.cancel()
            mutableSessionState.value = SessionState.Authenticated(session)
            val generation = sessionGeneration
            expiryJob = applicationScope.launch { expireAtDeadline(generation, session) }
            sessionEstablished = true
        }
        return SessionAuthenticationCompletion(outcome, sessionEstablished)
    }

    private fun expireCurrentIfNecessaryLocked() {
        val authenticated = mutableSessionState.value as? SessionState.Authenticated ?: return
        if (timeoutPolicy.isExpired(
                timeProvider.nowElapsedRealtimeMillis().coerceAtLeast(0),
                authenticated.session.expiresAtElapsedRealtimeMillis,
            )
        ) {
            clearSessionLocked(cancelExpiry = true)
        }
    }

    private fun clearSessionLocked(cancelExpiry: Boolean) {
        sessionGeneration = next(sessionGeneration)
        if (cancelExpiry) expiryJob?.cancel()
        expiryJob = null
        mutableSessionState.value = SessionState.Unauthenticated
    }

    private suspend fun expireAtDeadline(generation: Long, session: AuthenticatedSession) {
        while (true) {
            val remaining = mutex.withLock {
                val current = mutableSessionState.value as? SessionState.Authenticated
                if (generation != sessionGeneration || current?.session != session) {
                    null
                } else {
                    timeoutPolicy.remainingMillis(
                        timeProvider.nowElapsedRealtimeMillis(),
                        session.expiresAtElapsedRealtimeMillis,
                    )
                }
            } ?: return

            if (remaining > 0) delay(remaining)

            val shouldWaitAgain = mutex.withLock {
                val current = mutableSessionState.value as? SessionState.Authenticated
                if (generation != sessionGeneration || current?.session != session) {
                    false
                } else if (timeoutPolicy.isExpired(
                        timeProvider.nowElapsedRealtimeMillis().coerceAtLeast(0),
                        session.expiresAtElapsedRealtimeMillis,
                    )
                ) {
                    clearSessionLocked(cancelExpiry = false)
                    false
                } else {
                    true
                }
            }
            if (!shouldWaitAgain) return
        }
    }

    private fun next(value: Long): Long = if (value == Long.MAX_VALUE) 0 else value + 1
}
