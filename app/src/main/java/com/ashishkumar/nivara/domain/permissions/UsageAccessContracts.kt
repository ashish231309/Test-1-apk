package com.ashishkumar.nivara.domain.permissions

/** Usage Access is a Settings-managed special capability, not a runtime permission prompt. */
enum class UsageAccessStatus {
    GRANTED,
    NOT_GRANTED,
    UNAVAILABLE,
}

enum class UsageAccessSettingsResult {
    OPENED,
    UNAVAILABLE,
}

interface UsageAccessRepository {
    suspend fun status(): UsageAccessStatus

    /** Opens the platform's Usage Access settings when the device can resolve it. */
    fun openSettings(): UsageAccessSettingsResult
}

/** Android-free mapping after a platform query has completed successfully. */
object UsageAccessStatusMapping {
    fun fromGranted(granted: Boolean): UsageAccessStatus =
        if (granted) UsageAccessStatus.GRANTED else UsageAccessStatus.NOT_GRANTED
}
