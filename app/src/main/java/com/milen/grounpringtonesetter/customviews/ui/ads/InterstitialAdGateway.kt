package com.milen.grounpringtonesetter.customviews.ui.ads

import android.app.Activity

/** Minimal interstitial contract consumed by feature logic. */
internal interface InterstitialAdGateway {
    fun updateActivity(activity: Activity)

    fun setHostActive(active: Boolean) = Unit

    /** Detach the UI host; application-owned inventory may remain cached. */
    fun release() = Unit

    fun preloadInterstitialAd()

    fun showInterstitialAd(
        onAdLoadingFinished: (InterstitialAdShowResult) -> Unit = {},
    )
}
