package com.milen.grounpringtonesetter.utils

import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class BundleExtensionsTest {

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `parcelableOrThrow returns value on API 33`() {
        val bundle = Bundle()
        val uri = Uri.parse("content://test")
        bundle.putParcelable("key", uri)

        val result = bundle.parcelableOrThrow<Uri>("key")
        assertEquals(uri, result)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S_V2])
    fun `parcelableOrThrow returns value on API 32`() {
        val bundle = Bundle()
        val uri = Uri.parse("content://test")
        bundle.putParcelable("key", uri)

        val result = bundle.parcelableOrThrow<Uri>("key")
        assertEquals(uri, result)
    }

    @Test
    fun `parcelableArrayListOrEmpty returns empty list when missing`() {
        val bundle = Bundle()
        val result = bundle.parcelableArrayListOrEmpty<Uri>("missing")
        assertNotNull(result)
        assertEquals(0, result.size)
    }
}
