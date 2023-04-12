package com.milen.grounpringtonesetter.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

fun Context.hasInternetConnection(): Boolean =
    with(getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager) {
        this?.activeNetwork?.let {
            getNetworkCapabilities(it)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    } ?: false

fun Activity.openAppDetailsSettings(infoTextId: Int? = null) {
    infoTextId?.let {
        Toast.makeText(
            this,
            getString(it),
            Toast.LENGTH_LONG
        ).show()
    }

    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${packageName}")
    }.also {
        startActivity(it)
    }
}