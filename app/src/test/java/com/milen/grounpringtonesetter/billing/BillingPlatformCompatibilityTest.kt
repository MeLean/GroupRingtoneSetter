package com.milen.grounpringtonesetter.billing

import org.junit.Assert.assertEquals
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

    private data class CompatibilityExpectation(
        val sdkInt: Int,
        val hasRequiredMethod: Boolean,
        val compatible: Boolean,
    )
}
