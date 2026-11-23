package com.milen.grounpringtonesetter.customviews.ui.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterstitialAdPolicyTest {
    @Test
    fun `many consecutive operations may show without a display cooldown or session cap`() {
        val policy = InterstitialAdPolicy()
        repeat(20) {
            assertTrue(policy.canShow())
            assertTrue(policy.tryMarkFullscreenAdShowing())
            assertFalse(policy.canShow())
            assertFalse(policy.tryMarkFullscreenAdShowing())
            policy.onFullscreenAdClosed()
            assertTrue(policy.canLoad(0L))
        }
    }

    @Test
    fun `failure backoff caps at five minutes and successful load resets it`() {
        val policy = InterstitialAdPolicy()
        listOf(30_000L, 60_000L, 120_000L, 300_000L, 300_000L).forEach { delay ->
            policy.onLoadFailed(1_000L)
            assertEquals(1_000L + delay, policy.nextAllowedLoadAtMs())
            assertFalse(policy.canLoad(1_000L + delay - 1L))
            assertTrue(policy.canLoad(1_000L + delay))
        }
        policy.onLoadSucceeded()
        assertTrue(policy.canLoad(0L))
    }
}
