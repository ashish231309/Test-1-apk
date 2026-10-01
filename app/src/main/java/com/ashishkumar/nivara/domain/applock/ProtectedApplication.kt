package com.ashishkumar.nivara.domain.applock

/** A protected app is identified only by its Android package name. */
data class ProtectedApplication(val packageName: String) {
    init {
        require(PACKAGE_NAME.matches(packageName)) { "A normalized application package name is required." }
    }

    companion object {
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

sealed interface ProtectedApplicationsSnapshot {
    data class Available(val applications: Set<ProtectedApplication>) : ProtectedApplicationsSnapshot
    data object Unavailable : ProtectedApplicationsSnapshot
}

sealed interface ProtectedApplicationLookup {
    data object Protected : ProtectedApplicationLookup
    data object NotProtected : ProtectedApplicationLookup
    data object Unavailable : ProtectedApplicationLookup
}

enum class ProtectedApplicationUpdateResult {
    UPDATED,
    UNAVAILABLE,
}

/** Application-private configuration only; it never owns or mirrors authentication/session state. */
interface ProtectedApplicationRepository {
    suspend fun getProtectedApplications(): ProtectedApplicationsSnapshot
    suspend fun isProtected(packageName: String): ProtectedApplicationLookup
    suspend fun setProtected(
        application: ProtectedApplication,
        protected: Boolean,
    ): ProtectedApplicationUpdateResult

    /** Idempotent convenience operation; the repository remains the only protected-set owner. */
    suspend fun protect(application: ProtectedApplication): ProtectedApplicationUpdateResult =
        setProtected(application, protected = true)

    /** Idempotent convenience operation; the repository remains the only protected-set owner. */
    suspend fun unprotect(application: ProtectedApplication): ProtectedApplicationUpdateResult =
        setProtected(application, protected = false)
}
