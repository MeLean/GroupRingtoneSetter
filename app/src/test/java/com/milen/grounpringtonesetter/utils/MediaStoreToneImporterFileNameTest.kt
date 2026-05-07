package com.milen.grounpringtonesetter.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStoreToneImporterFileNameTest {

    @Test
    fun `normalizeToneFileName preserves Cyrillic Arabic Chinese and Japanese characters`() {
        val result = normalizeToneFileName(
            rawName = "Привет مرحبا 你好 こんにちは.mp3",
            mimeExtension = "mp3"
        )

        assertEquals("Привет مرحبا 你好 こんにちは.mp3", result)
    }

    @Test
    fun `normalizeToneFileName replaces only forbidden filename characters`() {
        val result = normalizeToneFileName(
            rawName = "موسيقى/مرحبا:你好?.mp3",
            mimeExtension = "mp3"
        )

        assertEquals("موسيقى_مرحبا_你好_.mp3", result)
    }

    @Test
    fun `normalizeToneFileName preserves non latin base name when correcting extension`() {
        val result = normalizeToneFileName(
            rawName = "铃声.tmp",
            mimeExtension = "mp3"
        )

        assertEquals("铃声.mp3", result)
    }

    @Test
    fun `normalizeToneFileName adds fallback extension when file name has none`() {
        val result = normalizeToneFileName(
            rawName = "着信音",
            mimeExtension = null
        )

        assertEquals("着信音.mp3", result)
    }

    @Test
    fun `normalizeToneFileName falls back when name becomes blank`() {
        val result = normalizeToneFileName(
            rawName = "////",
            mimeExtension = null
        )

        assertEquals("tone.mp3", result)
    }
}
