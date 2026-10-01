package com.ashishkumar.nivara.domain.applock

/** Android-free view of the special app-overlay capability required by the Stage 8 surface. */
enum class OverlayCapabilityStatus {
    GRANTED,
    NOT_GRANTED,
    UNAVAILABLE,
}

enum class OverlaySettingsResult {
    OPENED,
    FAILED,
}

interface OverlayCapabilityRepository {
    fun status(): OverlayCapabilityStatus
    fun openSettings(): OverlaySettingsResult
}
