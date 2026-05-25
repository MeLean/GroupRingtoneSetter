package com.milen.grounpringtonesetter

import com.milen.grounpringtonesetter.ui.ScreenInfoProvider
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

    @Test
    fun `shows app info directly when screen opts out from toolbar screen help`() {
        val spec = resolveMainInfoDialogSpec(HomeToolbarAppInfoProvider())

        assertTrue(spec.shouldShowAppInfoDirectly)
        assertNull(spec.screenMessageResId)
        assertNull(spec.secondaryActionTextResId)
    }

    @Test
    fun `keeps screen help in toolbar when provider allows it`() {
        val spec = resolveMainInfoDialogSpec(DefaultToolbarScreenInfoProvider())

        assertFalse(spec.shouldShowAppInfoDirectly)
        assertEquals(R.string.device_default_tones_info_text, spec.screenMessageResId)
        assertEquals(R.string.about_app, spec.secondaryActionTextResId)
    }

    private class HomeToolbarAppInfoProvider : ScreenInfoProvider {
        override fun getScreenInfoMessageResId(): Int = R.string.home_info_text

        override fun getToolbarInfoMessageResId(): Int? = null
    }

    private class DefaultToolbarScreenInfoProvider : ScreenInfoProvider {
        override fun getScreenInfoMessageResId(): Int = R.string.device_default_tones_info_text
    }
}
