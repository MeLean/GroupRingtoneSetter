package com.milen.grounpringtonesetter.billing

import android.app.ActivityOptions
import android.os.Build
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult

internal const val REQUIRED_BILLING_ACTIVITY_OPTIONS_METHOD =
    "ActivityOptions.setPendingIntentBackgroundActivityStartMode(int)"

internal fun interface BillingPlatformCapabilityDetector {
    fun isCompatible(): Boolean
}

internal object ReflectiveBillingPlatformCapabilityDetector : BillingPlatformCapabilityDetector {
    override fun isCompatible(): Boolean = isBillingPlatformCompatible(
        sdkInt = Build.VERSION.SDK_INT,
        hasPendingIntentBackgroundActivityStartModeMethod =
            hasPendingIntentBackgroundActivityStartModeMethod(),
    )

    private fun hasPendingIntentBackgroundActivityStartModeMethod(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true

        return runCatching {
            ActivityOptions::class.java.getMethod(
                "setPendingIntentBackgroundActivityStartMode",
                Int::class.javaPrimitiveType,
            )
        }.isSuccess
    }
}

internal object BillingPlatformCapabilities {
    @Volatile
    var detector: BillingPlatformCapabilityDetector = ReflectiveBillingPlatformCapabilityDetector

    fun isCompatible(): Boolean = detector.isCompatible()
}

internal fun isBillingPlatformCompatible(
    sdkInt: Int,
    hasPendingIntentBackgroundActivityStartModeMethod: Boolean,
): Boolean = sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
    hasPendingIntentBackgroundActivityStartModeMethod

internal fun isBillingPlatformCompatible(): Boolean = BillingPlatformCapabilities.isCompatible()

internal inline fun launchBillingFlowIfCompatible(
    capabilityDetector: BillingPlatformCapabilityDetector,
    launchBillingFlow: () -> BillingResult,
): BillingResult {
    if (capabilityDetector.isCompatible()) return launchBillingFlow()

    return BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
        .setDebugMessage("Billing platform incompatible")
        .build()
}
