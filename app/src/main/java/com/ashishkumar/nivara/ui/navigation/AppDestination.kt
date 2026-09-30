package com.ashishkumar.nivara.ui.navigation

/** Destinations available to the app's Compose navigation graph. */
sealed class AppDestination(val route: String) {
    data object Home : AppDestination("home")
}
