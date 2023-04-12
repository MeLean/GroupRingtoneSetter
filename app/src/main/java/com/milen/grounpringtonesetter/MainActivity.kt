package com.milen.grounpringtonesetter


import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.navigation.compose.rememberNavController
import com.google.android.gms.ads.MobileAds
import com.milen.grounpringtonesetter.navigation.Destination
import com.milen.grounpringtonesetter.navigation.MainNavHost
import com.milen.grounpringtonesetter.utils.hasInternetConnection


class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MobileAds.initialize(this) {}

        setContent {
            MainNavHost(
                navController = rememberNavController(),
                startDestination = getStartDestination()
            )
        }
    }


    private fun getStartDestination(): Destination =
        when {
            hasInternetConnection() -> Destination.HOME
            else -> Destination.NO_INTERNET
        }
}