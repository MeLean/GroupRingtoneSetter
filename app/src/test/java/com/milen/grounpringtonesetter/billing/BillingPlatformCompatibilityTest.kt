package com.milen.grounpringtonesetter.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingPlatformCompatibilityTest {

    @Test
    fun `compatibility depends on required method starting with API 34`() {
        val expectations = listOf(
            CompatibilityExpectation(sdkInt = 33, hasRequiredMethod = false, compatible = true),
            CompatibilityExpectation(sdkInt = 33, hasRequiredMethod = true, compatible = true),
            CompatibilityExpectation(sdkInt = 34, hasRequiredMethod = true, compatible = true),
            CompatibilityExpectation(sdkInt = 34, hasRequiredMethod = false, compatible = false),
            CompatibilityExpectation(sdkInt = 35, hasRequiredMethod = true, compatible = true),
            CompatibilityExpectation(sdkInt = 35, hasRequiredMethod = false, compatible = false),
        )

        expectations.forEach { expectation ->
            assertEquals(
                "Unexpected compatibility for SDK ${expectation.sdkInt} " +
                    "with method present=${expectation.hasRequiredMethod}",
                expectation.compatible,
                isBillingPlatformCompatible(
                    sdkInt = expectation.sdkInt,
                    hasPendingIntentBackgroundActivityStartModeMethod = expectation.hasRequiredMethod,
                ),
            )
        }
    }

    @Test
    fun `incompatible app controlled launch returns unavailable without invoking billing`() {
        var launchCalled = false

        val result = launchBillingFlowIfCompatible(
            capabilityDetector = BillingPlatformCapabilityDetector { false },
        ) {
            launchCalled = true
            successfulBillingResult()
        }

        assertEquals(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE, result.responseCode)
        assertFalse(launchCalled)
    }

    @Test
    fun `compatible app controlled launch delegates exactly once`() {
        var launchCount = 0

        val result = launchBillingFlowIfCompatible(
            capabilityDetector = BillingPlatformCapabilityDetector { true },
        ) {
            launchCount += 1
            successfulBillingResult()
        }

        assertEquals(BillingClient.BillingResponseCode.OK, result.responseCode)
        assertEquals(1, launchCount)
        assertTrue(result.debugMessage.isEmpty())
    }

    private fun successfulBillingResult(): BillingResult = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.OK)
        .build()

    private data class CompatibilityExpectation(
        val sdkInt: Int,
        val hasRequiredMethod: Boolean,
        val compatible: Boolean,
    )
}
