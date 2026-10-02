package com.ashishkumar.nivara.domain.apphide

/** A Nivara hidden-state entry is identified only by its exact Android package name. */
data class HiddenApplication(val packageName: String) {
    init {
        require(packageName.length <= MAX_PACKAGE_NAME_LENGTH && PACKAGE_NAME.matches(packageName)) {
            "A normalized application package name is required."
        }
    }

    companion object {
        private const val MAX_PACKAGE_NAME_LENGTH = 255
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

/** A readable empty set is distinct from a corrupt or inaccessible hidden-state file. */
sealed interface HiddenApplicationsSnapshot {
    data class Available(val applications: Set<HiddenApplication>) : HiddenApplicationsSnapshot
    data object Unreadable : HiddenApplicationsSnapshot
    data object Unavailable : HiddenApplicationsSnapshot
}

enum class HiddenApplicationUpdateResult {
    UPDATED,
    ALREADY_IN_STATE,
    UNREADABLE,
    UNAVAILABLE,
}

/** The single authority for persisted hidden-app state used by management and launcher consumers. */
interface HiddenApplicationRepository {
    suspend fun getHiddenApplications(): HiddenApplicationsSnapshot
    suspend fun hide(application: HiddenApplication): HiddenApplicationUpdateResult
    suspend fun unhide(application: HiddenApplication): HiddenApplicationUpdateResult
}
