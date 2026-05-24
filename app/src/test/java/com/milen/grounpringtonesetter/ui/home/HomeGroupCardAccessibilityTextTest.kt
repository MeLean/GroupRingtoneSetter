package com.milen.grounpringtonesetter.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeGroupCardAccessibilityTextTest {

    @Test
    fun `resolveRingtoneText returns fallback when ringtone is blank`() {
        val result = HomeGroupCardAccessibilityText.resolveRingtoneText(
            rawRingtoneText = "   ",
            noRingtoneLabel = "No ringtone assigned"
        )

        assertEquals("No ringtone assigned", result)
    }

    @Test
    fun `buildContactsLine formats label and count`() {
        val result = HomeGroupCardAccessibilityText.buildContactsAccessibilityText(
            contactsLabel = "Contacts",
            contactsCount = 7
        )

        assertEquals("Contacts 7", result)
    }

    @Test
    fun `buildRingtoneDisplayText keeps no ringtone label as is`() {
        val result = HomeGroupCardAccessibilityText.buildRingtoneDisplayText(
            ringtoneLabel = "Ringtone",
            ringtoneText = "No ringtone assigned",
            noRingtoneLabel = "No ringtone assigned"
        )

        assertEquals("No ringtone assigned", result)
    }

    @Test
    fun `buildRingtoneDisplayText prefixes assigned ringtone`() {
        val result = HomeGroupCardAccessibilityText.buildRingtoneDisplayText(
            ringtoneLabel = "Ringtone",
            ringtoneText = "Song.mp3",
            noRingtoneLabel = "No ringtone assigned"
        )

        assertEquals("Ringtone: Song.mp3", result)
    }
}
