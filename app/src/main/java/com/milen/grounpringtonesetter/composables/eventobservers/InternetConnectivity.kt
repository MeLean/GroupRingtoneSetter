package com.milen.grounpringtonesetter.composables.eventobservers

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable
fun InternetConnectivity(
    onConnectionAvailable: () -> Unit = {},
    onConnectionLost: () -> Unit = {}
) {
    val hasConnection: MutableState<Boolean?> = remember { mutableStateOf(null) }
    val connectivityManager =
        LocalContext.current.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val networkCallback = remember {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                hasConnection.value = true
            }

            override fun onLost(network: Network) {
                hasConnection.value = false
            }

            override fun onUnavailable() {
                hasConnection.value = false
            }
        }
    }

    LaunchedEffect(hasConnection.value) {
        when (hasConnection.value) {
            true -> onConnectionAvailable()
            false -> onConnectionLost()
            null -> Unit
        }
    }

    DisposableEffect(connectivityManager, networkCallback) {
        val networkRequest = NetworkRequest.Builder().apply {
            addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }.build()

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        onDispose {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }
}