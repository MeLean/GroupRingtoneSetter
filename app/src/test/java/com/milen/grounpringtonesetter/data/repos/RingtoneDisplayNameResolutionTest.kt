package com.milen.grounpringtonesetter.data.repos

import org.junit.Assert.assertEquals
import org.junit.Test

class RingtoneDisplayNameResolutionTest {

    @Test
    fun `chooseRingtoneDisplayName prefers persisted name`() {
        val result = chooseRingtoneDisplayName(
            persistedDisplayName = "Привет.mp3",
            queriedDisplayName = "tone.mp3",
            unavailableMarker = "File name not accessible",
            uriLastPathSegment = "tone.mp3",
            fallbackUri = "content://media/1"
        )

        assertEquals("Привет.mp3", result)
    }

    @Test
    fun `chooseRingtoneDisplayName ignores unavailable marker and uses uri segment`() {
        val result = chooseRingtoneDisplayName(
            persistedDisplayName = null,
            queriedDisplayName = "File name not accessible",
            unavailableMarker = "File name not accessible",
            uriLastPathSegment = "铃声.mp3",
            fallbackUri = "content://media/2"
        )

        assertEquals("铃声.mp3", result)
    }

    @Test
    fun `chooseRingtoneDisplayName falls back to uri when no better label exists`() {
        val result = chooseRingtoneDisplayName(
            persistedDisplayName = null,
            queriedDisplayName = null,
            unavailableMarker = "File name not accessible",
            uriLastPathSegment = null,
            fallbackUri = "content://media/3"
        )

        assertEquals("content://media/3", result)
    }
}
