package com.milen.grounpringtonesetter.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

fun Context.hasInternetConnection(): Boolean =
    with(getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager) {
        this?.activeNetwork?.let {
            getNetworkCapabilities(it)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    } ?: false