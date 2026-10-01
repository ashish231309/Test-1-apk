package com.ashishkumar.nivara.domain.setup

import com.ashishkumar.nivara.domain.app.ApplicationDiscoveryResult
import com.ashishkumar.nivara.domain.app.InstalledApplication
import com.ashishkumar.nivara.domain.permissions.UsageAccessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockSetupStateTest {
    @Test
    fun prerequisitesAreAvailableOnlyWhenDiscoveryAndUsageAccessAreAvailable() {
        val apps = ApplicationDiscoveryResult.Available(
            listOf(InstalledApplication("com.example.launcher", "Launcher", isLaunchable = true)),
        )

        assertTrue(AppLockSetupState.from(apps, UsageAccessStatus.GRANTED).prerequisitesAvailable)
        assertFalse(AppLockSetupState.from(apps, UsageAccessStatus.NOT_GRANTED).prerequisitesAvailable)
        assertFalse(AppLockSetupState.from(apps, UsageAccessStatus.UNAVAILABLE).prerequisitesAvailable)
    }

    @Test
    fun discoveryUnavailabilityIsRepresentedWithoutAndroidTypes() {
        val state = AppLockSetupState.from(
            discovery = ApplicationDiscoveryResult.Unavailable,
            usageAccess = UsageAccessStatus.GRANTED,
        )

        assertEquals(AppLockSetupState.ApplicationDiscovery.UNAVAILABLE, state.applicationDiscovery)
        assertFalse(state.prerequisitesAvailable)
    }

    @Test
    fun emptyButSuccessfulDiscoveryIsStillAvailable() {
        val state = AppLockSetupState.from(
            discovery = ApplicationDiscoveryResult.Available(emptyList()),
            usageAccess = UsageAccessStatus.GRANTED,
        )

        assertEquals(AppLockSetupState.ApplicationDiscovery.AVAILABLE, state.applicationDiscovery)
        assertTrue(state.prerequisitesAvailable)
    }
}
