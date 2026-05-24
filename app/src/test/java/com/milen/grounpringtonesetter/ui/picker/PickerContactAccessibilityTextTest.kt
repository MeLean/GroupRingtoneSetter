package com.milen.grounpringtonesetter.ui.picker

import org.junit.Assert.assertEquals
import org.junit.Test

class PickerContactAccessibilityTextTest {

    @Test
    fun `buildPhoneText prefixes phone number`() {
        val result = PickerContactAccessibilityText.buildPhoneText(
            phoneLabel = "Phone",
            phoneNumber = "+359123456"
        )

        assertEquals("Phone: +359123456", result)
    }

    @Test
    fun `buildPhoneText returns empty text when phone is missing`() {
        val result = PickerContactAccessibilityText.buildPhoneText(
            phoneLabel = "Phone",
            phoneNumber = " "
        )

        assertEquals("", result)
    }

    @Test
    fun `buildCheckboxDescription includes contact and group for unchecked contact`() {
        val result = PickerContactAccessibilityText.buildCheckboxDescription(
            isChecked = false,
            contactName = "Alex",
            groupName = "Family",
            includeTemplate = "Include %1\$s in %2\$s",
            removeTemplate = "Remove %1\$s from %2\$s"
        )

        assertEquals("Include Alex in Family", result)
    }
}
