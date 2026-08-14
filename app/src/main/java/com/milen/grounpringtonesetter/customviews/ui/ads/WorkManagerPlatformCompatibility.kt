package com.milen.grounpringtonesetter.customviews.ui.ads

import android.app.job.JobScheduler
import android.os.Build

internal fun isJobSchedulerNamespaceCompatible(
    sdkInt: Int,
    hasForNamespaceMethod: Boolean,
): Boolean = sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || hasForNamespaceMethod

internal fun isWorkManagerPlatformCompatible(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true

    val hasForNamespaceMethod = runCatching {
        JobScheduler::class.java.getMethod("forNamespace", String::class.java)
    }.isSuccess

    return isJobSchedulerNamespaceCompatible(
        sdkInt = Build.VERSION.SDK_INT,
        hasForNamespaceMethod = hasForNamespaceMethod,
    )
}
