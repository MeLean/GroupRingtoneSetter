package com.milen.grounpringtonesetter.customviews.ui.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterstitialAdPolicyTest {

    @Test
    fun `show is blocked until cooldown expires after impression`() {
        val policy = InterstitialAdPolicy()
        val firstImpressionAt = 1_000L

        policy.onAdImpression(firstImpressionAt)

        assertFalse(policy.canShow(firstImpressionAt + 30_000L))
        assertTrue(
            policy.canShow(
                firstImpressionAt + InterstitialAdPolicy.MIN_INTERSTITIAL_INTERVAL_MS
            )
        )
    }

    @Test
    fun `show is blocked after max session impressions`() {
        val policy = InterstitialAdPolicy()
        var nowMs = 0L

        repeat(InterstitialAdPolicy.MAX_INTERSTITIALS_PER_SESSION) {
            policy.onAdImpression(nowMs)
            nowMs += InterstitialAdPolicy.MIN_INTERSTITIAL_INTERVAL_MS
        }

        assertFalse(policy.canShow(nowMs))
    }

    @Test
    fun `load failures increase backoff and success resets it`() {
        val policy = InterstitialAdPolicy()
        val firstFailureAt = 5_000L

        policy.onLoadFailed(firstFailureAt)
        assertEquals(35_000L, policy.nextAllowedLoadAtMs())
        assertFalse(policy.canLoad(firstFailureAt + 29_999L))

        val secondFailureAt = policy.nextAllowedLoadAtMs()
        policy.onLoadFailed(secondFailureAt)
        assertEquals(secondFailureAt + 60_000L, policy.nextAllowedLoadAtMs())

        policy.onLoadSucceeded()

        assertEquals(0L, policy.nextAllowedLoadAtMs())
        assertTrue(policy.canLoad(secondFailureAt))
    }
}
