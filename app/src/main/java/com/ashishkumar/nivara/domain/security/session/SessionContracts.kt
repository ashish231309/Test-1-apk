package com.ashishkumar.nivara.domain.security.session

import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticationResult
import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import kotlinx.coroutines.flow.StateFlow

/** Authentication factor used for the current Nivara session; never a primary credential type. */
enum class AuthenticationSource { PRIMARY, BIOMETRIC }

/** Immutable authorization state. Session values contain no credentials, keys, tokens, or persisted data. */
sealed interface SessionState {
    data object Unauthenticated : SessionState
    data class Authenticated(val session: AuthenticatedSession) : SessionState
}

data class AuthenticatedSession(
    val source: AuthenticationSource,
    val authenticatedAtElapsedRealtimeMillis: Long,
    val expiresAtElapsedRealtimeMillis: Long,
) {
    init {
        require(authenticatedAtElapsedRealtimeMillis >= 0)
        require(expiresAtElapsedRealtimeMillis >= authenticatedAtElapsedRealtimeMillis)
    }
}

data class SessionAuthenticationCompletion<out T>(
    val outcome: T,
    val sessionEstablished: Boolean,
)

/** One process-scoped authority for session establishment, expiry, sensitive access and explicit locking. */
interface SessionManager {
    val sessionState: StateFlow<SessionState>

    /** Invokes the existing primary service and creates a session only from its successful typed outcome. */
    suspend fun authenticatePrimary(
        authenticate: suspend () -> AuthenticationResult,
    ): SessionAuthenticationCompletion<AuthenticationResult>

    /** Invokes the existing biometric service and creates a session only from its successful typed outcome. */
    suspend fun authenticateBiometric(
        authenticate: suspend () -> BiometricAuthenticationResult,
    ): SessionAuthenticationCompletion<BiometricAuthenticationResult>

    /** Validates expiry as part of reading; exact expiry time is unauthenticated. */
    suspend fun currentState(): SessionState

    /** The single authorization check future sensitive features must consult before access. */
    suspend fun mayAccessSensitiveContent(): Boolean

    /** Immediately revokes the current session and invalidates any already-running auth attempt. */
    suspend fun lockNow()
}
