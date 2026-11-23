package com.milen.grounpringtonesetter.customviews.ui.ads

internal class InterstitialAdPolicy {
    private var consecutiveLoadFailures = 0
    private var nextAllowedLoadAtMs = 0L
    private var isFullscreenAdShowing = false

    fun canLoad(nowMs: Long): Boolean = nowMs >= nextAllowedLoadAtMs

    fun canShow(): Boolean = !isFullscreenAdShowing

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

    fun onFullscreenAdClosed() {
        isFullscreenAdShowing = false
    }

    fun nextAllowedLoadAtMs(): Long = nextAllowedLoadAtMs

    companion object {
        private val LOAD_BACKOFF_MS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)
    }
}
