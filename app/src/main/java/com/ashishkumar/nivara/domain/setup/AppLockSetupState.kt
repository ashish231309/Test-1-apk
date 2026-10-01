package com.ashishkumar.nivara.domain.setup

import com.ashishkumar.nivara.domain.app.ApplicationDiscoveryResult
import com.ashishkumar.nivara.domain.permissions.UsageAccessStatus

/** Platform-independent snapshot of the prerequisites shown by the Stage 6 preparation screen. */
data class AppLockSetupState(
    val applicationDiscovery: ApplicationDiscovery,
    val usageAccess: UsageAccessStatus,
) {
    val prerequisitesAvailable: Boolean
        get() = applicationDiscovery == ApplicationDiscovery.AVAILABLE &&
            usageAccess == UsageAccessStatus.GRANTED

    enum class ApplicationDiscovery {
        AVAILABLE,
        UNAVAILABLE,
    }

    companion object {
        fun from(
            discovery: ApplicationDiscoveryResult,
            usageAccess: UsageAccessStatus,
        ): AppLockSetupState = AppLockSetupState(
            applicationDiscovery = when (discovery) {
                is ApplicationDiscoveryResult.Available -> ApplicationDiscovery.AVAILABLE
                ApplicationDiscoveryResult.Unavailable -> ApplicationDiscovery.UNAVAILABLE
            },
            usageAccess = usageAccess,
        )
    }
}
