package com.milen.grounpringtonesetter.ui.defaulttones

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDefaultToneTypeTest {

    @Test
    fun `ringtone does not allow silent selection`() {
        assertFalse(DeviceDefaultToneType.RINGTONE.allowSilentSelection)
    }

    @Test
    fun `notification and alarm allow silent selection`() {
        assertTrue(DeviceDefaultToneType.NOTIFICATION.allowSilentSelection)
        assertTrue(DeviceDefaultToneType.ALARM.allowSilentSelection)
    }
}
