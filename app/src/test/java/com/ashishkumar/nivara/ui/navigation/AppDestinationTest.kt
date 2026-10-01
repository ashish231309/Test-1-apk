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
}
