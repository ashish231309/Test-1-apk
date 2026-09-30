package com.ashishkumar.nivara.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ashishkumar.nivara.ui.home.HomeScreen
import com.ashishkumar.nivara.ui.navigation.AppDestination

@Composable
fun NivaraApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = AppDestination.Home.route,
    ) {
        composable(AppDestination.Home.route) {
            HomeScreen()
        }
    }
}
