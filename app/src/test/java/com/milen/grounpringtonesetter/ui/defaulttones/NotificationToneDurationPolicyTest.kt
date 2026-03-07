package com.milen.grounpringtonesetter.ui.defaulttones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationToneDurationPolicyTest {

    @Test
    fun `9_9 seconds is valid`() {
        val failure = NotificationToneDurationPolicy.getFailureReason(9_900L)
        assertNull(failure)
    }

    @Test
    fun `10 seconds is valid`() {
        val failure = NotificationToneDurationPolicy.getFailureReason(10_000L)
        assertNull(failure)
    }

    @Test
    fun `10_1 seconds is rejected as too long`() {
        val failure = NotificationToneDurationPolicy.getFailureReason(10_100L)
        assertEquals(DefaultToneImportFailureReason.NOTIFICATION_TONE_TOO_LONG, failure)
    }

    @Test
    fun `unknown duration is rejected`() {
        val failure = NotificationToneDurationPolicy.getFailureReason(null)
        assertEquals(DefaultToneImportFailureReason.NOTIFICATION_TONE_DURATION_UNKNOWN, failure)
    }
}
