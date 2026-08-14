package com.milen.grounpringtonesetter.customviews.ui.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkManagerPlatformCompatibilityTest {

    @Test
    fun `Android 13 does not require JobScheduler namespace support`() {
        assertTrue(
            isJobSchedulerNamespaceCompatible(
                sdkInt = 33,
                hasForNamespaceMethod = false,
            )
        )
    }

    @Test
    fun `Android 14 is compatible when JobScheduler namespace support exists`() {
        assertTrue(
            isJobSchedulerNamespaceCompatible(
                sdkInt = 34,
                hasForNamespaceMethod = true,
            )
        )
    }

    @Test
    fun `Android 14 is rejected when framework reports the API but omits its method`() {
        assertFalse(
            isJobSchedulerNamespaceCompatible(
                sdkInt = 34,
                hasForNamespaceMethod = false,
            )
        )
    }
}
