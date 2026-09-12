package com.milen.grounpringtonesetter.utils

import android.content.Intent
import android.net.Uri
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class IntentExtensionsTest {

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `getParcelableExtraCompat returns value from type-safe API on API 33`() {
        val uri = Uri.parse("content://media/external/audio/media/1")
        val intent = Intent().apply {
            putExtra("key", uri)
        }

        val result = intent.getParcelableExtraCompat("key", Uri::class.java)
        assertEquals(uri, result)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S_V2])
    fun `getParcelableExtraCompat returns value from legacy API on API 32`() {
        val uri = Uri.parse("content://media/external/audio/media/1")
        val intent = Intent().apply {
            putExtra("key", uri)
        }

        val result = intent.getParcelableExtraCompat("key", Uri::class.java)
        assertEquals(uri, result)
    }
}
