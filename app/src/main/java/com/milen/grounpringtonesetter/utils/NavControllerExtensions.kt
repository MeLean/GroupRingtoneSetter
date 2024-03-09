package com.milen.grounpringtonesetter.utils

import androidx.annotation.IdRes
import androidx.navigation.NavController
import androidx.navigation.NavOptions

fun NavController.navigateSingleTop(@IdRes resId: Int) =
    navigate(
        resId,
        null,
        NavOptions.Builder()
            .setLaunchSingleTop(true)
            .build()
    )
