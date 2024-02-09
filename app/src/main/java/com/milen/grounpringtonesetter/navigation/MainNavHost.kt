package com.milen.grounpringtonesetter.navigation

import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.milen.grounpringtonesetter.screens.home.HomeScreen
import com.milen.grounpringtonesetter.screens.home.HomeViewModel
import com.milen.grounpringtonesetter.screens.nointernet.NoInternetScreen

@Composable
@ExperimentalFoundationApi
fun MainNavHost(
    navController: NavHostController,
    startDestination: Destination = Destination.HOME
): Unit = NavHost(
    navController = navController,
    startDestination = startDestination.route,
) {


    composable(route = Destination.HOME.route) {
        val ctx = LocalContext.current as ComponentActivity
        val viewModel: HomeViewModel by ctx.viewModels()
        viewModel.run {
            HomeScreen(
                callbacks = viewModel.getHomeViewModelCallbacks(),
                navigate = navController::navigate,
                onFinish = { ctx.finish() },
            )
        }
    }

    composable(route = Destination.NO_INTERNET.route) {
        NoInternetScreen(
            navigate = navController::navigate
        )
    }
}

enum class Destination(val route: String) {
    HOME(route = "home"),
    NO_INTERNET(route = "no_internet")
}
