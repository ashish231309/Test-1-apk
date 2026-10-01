package com.ashishkumar.nivara.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class AppDestinationTest {
    @Test
    fun homeDestinationUsesStableRoute() {
        assertEquals("home", AppDestination.Home.route)
    }

    @Test
    fun appLockSetupDestinationUsesStableRoute() {
        assertEquals("app-lock/setup", AppDestination.AppLockSetup.route)
    }

    @Test
    fun appLockManagementDestinationUsesStableRoute() {
        assertEquals("app-lock/manage", AppDestination.AppLockManagement.route)
    }

    @Test
    fun hiddenApplicationManagementDestinationUsesStableRoute() {
        assertEquals("app-hide/manage", AppDestination.HiddenApplicationManagement.route)
    }

    @Test
    fun vaultDestinationUsesStableRoute() {
        assertEquals("vault", AppDestination.Vault.route)
    }

    @Test
    fun vaultRootConfigurationDestinationUsesStableRoute() {
        assertEquals("vault/root-configuration", AppDestination.VaultRootConfiguration.route)
    }
}
