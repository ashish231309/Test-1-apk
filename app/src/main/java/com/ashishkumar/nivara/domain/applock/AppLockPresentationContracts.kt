package com.ashishkumar.nivara.domain.applock

import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialType

/** Ephemeral identity binding one authentication surface to one package request. */
data class ProtectionRequest(val requestId: Long, val packageName: String) {
    init {
        require(requestId > 0)
        ProtectedApplication(packageName)
    }
}

enum class AuthenticationFactor { PRIMARY, BIOMETRIC }

enum class AppLockPresentationFailure {
    OVERLAY_PERMISSION_NOT_GRANTED,
    OVERLAY_PERMISSION_UNAVAILABLE,
    WINDOW_MANAGER_UNAVAILABLE,
    PRIMARY_CREDENTIAL_UNAVAILABLE,
    PROTECTED_APPLICATION_UNAVAILABLE,
    REQUEST_NO_LONGER_VALID,
}

enum class AppLockOverlayFeedback {
    PRIMARY_CREDENTIAL_REJECTED,
    PRIMARY_CREDENTIAL_TEMPORARILY_BLOCKED,
    PRIMARY_CREDENTIAL_UNAVAILABLE,
    PATTERN_INCOMPLETE,
    BIOMETRIC_CANCELLED,
    BIOMETRIC_FAILED,
    BIOMETRIC_UNAVAILABLE,
    PROTECTION_STATUS_UNAVAILABLE,
    AUTHENTICATION_SUPERSEDED,
}

sealed interface AppLockPresentationState {
    data object Idle : AppLockPresentationState
    data class Showing(
        val request: ProtectionRequest,
        val primaryCredentialType: PrimaryCredentialType?,
        val feedback: AppLockOverlayFeedback? = null,
        val retryAfterMillis: Long? = null,
    ) : AppLockPresentationState
    data class Authenticating(
        val request: ProtectionRequest,
        val factor: AuthenticationFactor,
        val primaryCredentialType: PrimaryCredentialType?,
    ) : AppLockPresentationState
    data class Dismissing(val request: ProtectionRequest) : AppLockPresentationState
    data class Failed(
        val request: ProtectionRequest,
        val reason: AppLockPresentationFailure,
    ) : AppLockPresentationState
}

/**
 * Synchronized, Android-free owner of presentation transitions. Request IDs are transient and never placed
 * in an Intent or persisted. A stale completion can only mutate the exact request ID that began it.
 */
class AppLockPresentationStateMachine {
    private val lock = Any()
    private var nextId = 0L
    private var current: AppLockPresentationState = AppLockPresentationState.Idle

    val state: AppLockPresentationState
        get() = synchronized(lock) { current }

    fun requestAuthentication(packageName: String): ProtectionRequest? = synchronized(lock) {
        val existing = current.requestOrNull()
        if (existing?.packageName == packageName && current !is AppLockPresentationState.Dismissing) {
            return@synchronized null
        }
        nextId = if (nextId == Long.MAX_VALUE) 1L else nextId + 1L
        val request = ProtectionRequest(nextId, packageName)
        current = AppLockPresentationState.Showing(request, primaryCredentialType = null)
        request
    }

    fun isCurrent(requestId: Long): Boolean = synchronized(lock) {
        current.requestOrNull()?.requestId == requestId
    }

    fun currentRequest(): ProtectionRequest? = synchronized(lock) { current.requestOrNull() }

    fun setPrimaryCredentialType(requestId: Long, type: PrimaryCredentialType?): Boolean = synchronized(lock) {
        val showing = current as? AppLockPresentationState.Showing ?: return@synchronized false
        if (showing.request.requestId != requestId) return@synchronized false
        current = showing.copy(primaryCredentialType = type)
        true
    }

    fun beginAuthentication(requestId: Long, factor: AuthenticationFactor): Boolean = synchronized(lock) {
        val showing = current as? AppLockPresentationState.Showing ?: return@synchronized false
        if (showing.request.requestId != requestId) return@synchronized false
        current = AppLockPresentationState.Authenticating(showing.request, factor, showing.primaryCredentialType)
        true
    }

    fun clearFeedback(requestId: Long): Boolean = synchronized(lock) {
        val showing = current as? AppLockPresentationState.Showing ?: return@synchronized false
        if (showing.request.requestId != requestId || showing.feedback == null) return@synchronized false
        current = showing.copy(feedback = null, retryAfterMillis = null)
        true
    }

    fun showFeedback(
        requestId: Long,
        feedback: AppLockOverlayFeedback,
        retryAfterMillis: Long? = null,
    ): Boolean = synchronized(lock) {
        val state = current
        val request = state.requestOrNull() ?: return@synchronized false
        if (request.requestId != requestId || state is AppLockPresentationState.Dismissing ||
            state is AppLockPresentationState.Failed
        ) {
            return@synchronized false
        }
        val type = when (state) {
            is AppLockPresentationState.Showing -> state.primaryCredentialType
            is AppLockPresentationState.Authenticating -> state.primaryCredentialType
            else -> null
        }
        current = AppLockPresentationState.Showing(request, type, feedback, retryAfterMillis)
        true
    }

    fun fail(requestId: Long, reason: AppLockPresentationFailure): Boolean = synchronized(lock) {
        val request = current.requestOrNull() ?: return@synchronized false
        if (request.requestId != requestId || current is AppLockPresentationState.Dismissing) {
            return@synchronized false
        }
        current = AppLockPresentationState.Failed(request, reason)
        true
    }

    fun retry(requestId: Long): Boolean = synchronized(lock) {
        val failed = current as? AppLockPresentationState.Failed ?: return@synchronized false
        if (failed.request.requestId != requestId) return@synchronized false
        current = AppLockPresentationState.Showing(failed.request, primaryCredentialType = null)
        true
    }

    /** Begins cleanup exactly once. Repeated cleanup requests are harmless. */
    fun beginDismiss(requestId: Long): Boolean = synchronized(lock) {
        val request = current.requestOrNull() ?: return@synchronized false
        if (request.requestId != requestId || current is AppLockPresentationState.Dismissing) {
            return@synchronized false
        }
        current = AppLockPresentationState.Dismissing(request)
        true
    }

    fun finishDismiss(requestId: Long): Boolean = synchronized(lock) {
        val dismissing = current as? AppLockPresentationState.Dismissing ?: return@synchronized false
        if (dismissing.request.requestId != requestId) return@synchronized false
        current = AppLockPresentationState.Idle
        true
    }

    /** Invalidates whichever request is current and returns it so the host can cancel work and remove its view. */
    fun invalidate(): ProtectionRequest? = synchronized(lock) {
        val request = current.requestOrNull() ?: return@synchronized null
        if (current !is AppLockPresentationState.Dismissing) {
            current = AppLockPresentationState.Dismissing(request)
        }
        request
    }

    private fun AppLockPresentationState.requestOrNull(): ProtectionRequest? = when (this) {
        AppLockPresentationState.Idle -> null
        is AppLockPresentationState.Showing -> request
        is AppLockPresentationState.Authenticating -> request
        is AppLockPresentationState.Dismissing -> request
        is AppLockPresentationState.Failed -> request
    }
}
