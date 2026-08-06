package com.milen.grounpringtonesetter.customviews.ui.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AdLifecycleClassificationTest {

    @Test
    fun `activity unavailable while showing is skipped`() {
        assertEquals(
            InterstitialAdShowResult.SKIPPED,
            interstitialResultWhenActivityUnavailable(),
        )
    }

    @Test
    fun `activity unavailable is not recorded as a non fatal error`() {
        assertFalse(shouldTrackAdStateAsError("activity_unavailable"))
    }
}
