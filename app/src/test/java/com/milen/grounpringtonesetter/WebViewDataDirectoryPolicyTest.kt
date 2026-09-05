package com.milen.grounpringtonesetter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebViewDataDirectoryPolicyTest {

    @Test
    fun `Android 8 does not configure a WebView data directory suffix`() {
        assertNull(webViewDataDirectorySuffix(sdkInt = 27))
    }

    @Test
    fun `Android 9 and newer use an isolated stable directory`() {
        assertEquals("main", webViewDataDirectorySuffix(sdkInt = 28))
        assertEquals("main", webViewDataDirectorySuffix(sdkInt = 36))
    }
}
