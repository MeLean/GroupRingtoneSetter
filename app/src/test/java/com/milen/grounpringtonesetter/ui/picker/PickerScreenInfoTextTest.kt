package com.milen.grounpringtonesetter.ui.picker

import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.ui.picker.data.PickerMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PickerScreenInfoTextTest {

    @Test
    fun `getMessageResId returns create help for create mode`() {
        val result = PickerScreenInfoText.getMessageResId(PickerMode.CREATE)

        assertEquals(R.string.picker_create_info_text, result)
    }

    @Test
    fun `getMessageResId returns rename help for rename mode`() {
        val result = PickerScreenInfoText.getMessageResId(PickerMode.RENAME)

        assertEquals(R.string.picker_rename_info_text, result)
    }

    @Test
    fun `getMessageResId returns manage help for manage mode`() {
        val result = PickerScreenInfoText.getMessageResId(PickerMode.MANAGE)

        assertEquals(R.string.picker_manage_info_text, result)
    }
}
