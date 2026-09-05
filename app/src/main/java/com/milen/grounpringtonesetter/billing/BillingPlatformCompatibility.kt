package com.milen.grounpringtonesetter.billing

import android.app.ActivityOptions
import android.os.Build

internal const val REQUIRED_BILLING_ACTIVITY_OPTIONS_METHOD =
    "ActivityOptions.setPendingIntentBackgroundActivityStartMode(int)"

internal fun isBillingPlatformCompatible(
    sdkInt: Int,
    hasPendingIntentBackgroundActivityStartModeMethod: Boolean,
): Boolean = sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
    hasPendingIntentBackgroundActivityStartModeMethod

internal fun isBillingPlatformCompatible(): Boolean {
    val sdkInt = Build.VERSION.SDK_INT
    if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return true
    }

    val hasRequiredMethod = runCatching {
        ActivityOptions::class.java.getMethod(
            "setPendingIntentBackgroundActivityStartMode",
            Int::class.javaPrimitiveType,
        )
    }.isSuccess

    return isBillingPlatformCompatible(
        sdkInt = sdkInt,
        hasPendingIntentBackgroundActivityStartModeMethod = hasRequiredMethod,
    )
}
