package com.milen.grounpringtonesetter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainInfoDialogSpecTest {

    @Test
    fun `shows app info directly when screen info is unavailable`() {
        val spec = resolveMainInfoDialogSpec(screenMessageResId = null)

        assertTrue(spec.shouldShowAppInfoDirectly)
        assertNull(spec.screenMessageResId)
        assertNull(spec.secondaryActionTextResId)
    }

    @Test
    fun `keeps app info shortcut when screen info is available`() {
        val spec = resolveMainInfoDialogSpec(
            screenMessageResId = R.string.device_default_tones_info_text
        )

        assertFalse(spec.shouldShowAppInfoDirectly)
        assertEquals(R.string.device_default_tones_info_text, spec.screenMessageResId)
        assertEquals(R.string.about_app, spec.secondaryActionTextResId)
    }
}
