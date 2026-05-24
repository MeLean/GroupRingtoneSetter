package com.milen.grounpringtonesetter.customviews.ui.ads

internal class InterstitialAdPolicy {
    private var sessionInterstitialShows = 0
    private var lastInterstitialImpressionAtMs = 0L
    private var consecutiveLoadFailures = 0
    private var nextAllowedLoadAtMs = 0L
    private var isFullscreenAdShowing = false

    fun canLoad(nowMs: Long): Boolean = nowMs >= nextAllowedLoadAtMs

    fun canShow(nowMs: Long): Boolean {
        if (isFullscreenAdShowing) return false
        if (sessionInterstitialShows >= MAX_INTERSTITIALS_PER_SESSION) return false
        if (lastInterstitialImpressionAtMs == 0L) return true
        return nowMs - lastInterstitialImpressionAtMs >= MIN_INTERSTITIAL_INTERVAL_MS
    }

    fun tryMarkFullscreenAdShowing(): Boolean {
        if (isFullscreenAdShowing) return false
        isFullscreenAdShowing = true
        return true
    }

    fun onLoadSucceeded() {
        consecutiveLoadFailures = 0
        nextAllowedLoadAtMs = 0L
    }

    fun onLoadFailed(nowMs: Long) {
        val backoffIndex = consecutiveLoadFailures.coerceAtMost(LOAD_BACKOFF_MS.lastIndex)
        nextAllowedLoadAtMs = nowMs + LOAD_BACKOFF_MS[backoffIndex]
        consecutiveLoadFailures += 1
    }

    fun onAdImpression(nowMs: Long) {
        lastInterstitialImpressionAtMs = nowMs
        sessionInterstitialShows += 1
    }

    fun onFullscreenAdClosed() {
        isFullscreenAdShowing = false
    }

    fun nextAllowedLoadAtMs(): Long = nextAllowedLoadAtMs

    companion object {
        internal const val MIN_INTERSTITIAL_INTERVAL_MS = 90_000L
        internal const val MAX_INTERSTITIALS_PER_SESSION = 4
        private val LOAD_BACKOFF_MS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)
    }
}
