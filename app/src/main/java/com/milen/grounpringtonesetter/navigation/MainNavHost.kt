package com.milen.grounpringtonesetter.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.milen.grounpringtonesetter.screens.home.HomeScreen
import com.milen.grounpringtonesetter.screens.nointernet.NoInternetScreen
import com.milen.grounpringtonesetter.screens.picker.PickerScreen
import com.milen.grounpringtonesetter.screens.viewmodel.MainViewModel
import com.milen.grounpringtonesetter.screens.viewmodel.MainViewModelFactory
import com.milen.grounpringtonesetter.utils.ContactsHelper

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
        val viewModel: MainViewModel =
            viewModel(factory = MainViewModelFactory(ContactsHelper(ctx.application)))

        HomeScreen(
            callbacks = viewModel.getHomeViewModelCallbacks(),
            navigate = navController::navigate,
            onFinish = { ctx.finish() },
        )
    }

    composable(route = Destination.PICKER.route) {
        val ctx = LocalContext.current as ComponentActivity
        val viewModel: MainViewModel =
            viewModel(factory = MainViewModelFactory(ContactsHelper(ctx.application)))

        PickerScreen(
            callbacks = viewModel.getPickerViewModelCallbacks(),
            onDone = {
                viewModel.onPickerResult(it)
                navController.popBackStack()
            },
            goBack = navController::popBackStack,
        )
    }

    composable(route = Destination.NO_INTERNET.route) {
        NoInternetScreen(navigate = navController::navigate)
    }
}

enum class Destination(val route: String) {
    HOME(route = "home"),
    PICKER(route = "picker"),
    NO_INTERNET(route = "no_internet")
}
