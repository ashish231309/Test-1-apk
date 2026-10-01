package com.ashishkumar.nivara.domain.applock

import com.ashishkumar.nivara.domain.security.session.SessionState

sealed interface ProtectionDecision {
    data object NoProtectionRequired : ProtectionDecision
    data class AuthenticationRequired(val packageName: String) : ProtectionDecision
}

object ProtectionDecider {
    fun decide(
        foreground: ForegroundApplication?,
        protectedApplications: Set<ProtectedApplication>,
        sessionState: SessionState,
        nivaraPackageName: String,
    ): ProtectionDecision {
        if (foreground == null || foreground.packageName == nivaraPackageName) {
            return ProtectionDecision.NoProtectionRequired
        }
        if (protectedApplications.none { it.packageName == foreground.packageName }) {
            return ProtectionDecision.NoProtectionRequired
        }
        if (sessionState is SessionState.Authenticated) {
            return ProtectionDecision.NoProtectionRequired
        }
        return ProtectionDecision.AuthenticationRequired(foreground.packageName)
    }
}

sealed interface ProtectionEvent {
    data class AuthenticationRequired(val packageName: String) : ProtectionEvent
}

/** Suppresses repeats while the same unauthorized protected app remains foreground. */
class AuthenticationRequestDebouncer {
    private var announcedPackage: String? = null
    val pendingPackage: String? get() = announcedPackage

    fun eventFor(decision: ProtectionDecision): ProtectionEvent? = when (decision) {
        ProtectionDecision.NoProtectionRequired -> {
            announcedPackage = null
            null
        }
        is ProtectionDecision.AuthenticationRequired -> {
            if (announcedPackage == decision.packageName) {
                null
            } else {
                announcedPackage = decision.packageName
                ProtectionEvent.AuthenticationRequired(decision.packageName)
            }
        }
    }

    fun reset() {
        announcedPackage = null
    }
}
