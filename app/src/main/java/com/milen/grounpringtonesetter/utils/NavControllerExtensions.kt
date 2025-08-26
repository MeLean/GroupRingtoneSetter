package com.milen.grounpringtonesetter.utils

import android.os.Bundle
import androidx.annotation.IdRes
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.navOptions

internal fun NavController.navigateSingleTop(
    @IdRes resId: Int,
    args: Bundle? = null,
    @IdRes popUpToId: Int? = null,
    inclusive: Boolean = false,
    builder: NavOptionsBuilder.() -> Unit = {},
) {
    navigate(resId, args, navOptions {
        launchSingleTop = true
        popUpToId?.let { popUpTo(it) { this.inclusive = inclusive } }
        builder()
    })
}

internal fun NavController.navigateAsRoot(@IdRes resId: Int, args: Bundle? = null) {
    navigate(resId, args, navOptions {
        launchSingleTop = true
        popUpTo(graph.startDestinationId) { inclusive = true }
    })
}
