package com.ashishkumar.nivara.domain.applock

import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticationResult
import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialService
import com.ashishkumar.nivara.domain.security.session.SessionAuthenticationCompletion
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import kotlinx.coroutines.CancellationException

sealed interface AppLockAuthenticationRouteResult<out T> {
    data object ExistingSession : AppLockAuthenticationRouteResult<Nothing>
    data object RequestInvalidated : AppLockAuthenticationRouteResult<Nothing>
    data class Completed<T>(
        val completion: SessionAuthenticationCompletion<T>,
    ) : AppLockAuthenticationRouteResult<T>
}

/**
 * Routes Stage 8 attempts through existing Stage 3/4 services and the sole Stage 5 session authority.
 * Request validity is checked after factor success but before SessionManager can establish a session.
 */
class AppLockAuthenticationRouter(
    private val primaryCredentialService: PrimaryCredentialService,
    private val sessionManager: SessionManager,
) {
    suspend fun authenticatePrimary(
        credential: CharArray,
        requestStillValid: suspend () -> Boolean,
    ): AppLockAuthenticationRouteResult<AuthenticationResult> {
        return try {
            if (sessionManager.currentState() is SessionState.Authenticated) {
                return AppLockAuthenticationRouteResult.ExistingSession
            }
            val completion = sessionManager.authenticatePrimary {
                val outcome = primaryCredentialService.authenticate(credential)
                if (outcome is AuthenticationResult.Authenticated && !requestStillValid()) {
                    throw AppLockRequestInvalidated()
                }
                outcome
            }
            AppLockAuthenticationRouteResult.Completed(completion)
        } catch (_: AppLockRequestInvalidated) {
            AppLockAuthenticationRouteResult.RequestInvalidated
        } finally {
            credential.fill('\u0000')
        }
    }

    suspend fun authenticateBiometric(
        authenticate: suspend () -> BiometricAuthenticationResult,
        requestStillValid: suspend () -> Boolean,
    ): AppLockAuthenticationRouteResult<BiometricAuthenticationResult> {
        return try {
            if (sessionManager.currentState() is SessionState.Authenticated) {
                return AppLockAuthenticationRouteResult.ExistingSession
            }
            val completion = sessionManager.authenticateBiometric {
                val outcome = authenticate()
                if (outcome == BiometricAuthenticationResult.Authenticated && !requestStillValid()) {
                    throw AppLockRequestInvalidated()
                }
                outcome
            }
            AppLockAuthenticationRouteResult.Completed(completion)
        } catch (_: AppLockRequestInvalidated) {
            AppLockAuthenticationRouteResult.RequestInvalidated
        }
    }

    private class AppLockRequestInvalidated : CancellationException("The App Lock request is no longer current.")
}
