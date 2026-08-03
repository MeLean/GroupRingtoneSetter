package com.milen.grounpringtonesetter.customviews.ui.ads

import android.app.Activity

/** Minimal interstitial contract consumed by feature logic. */
internal interface InterstitialAdGateway {
    fun updateActivity(activity: Activity)

    fun preloadInterstitialAd()

    fun showInterstitialAd(
        onAdLoadingFinished: (InterstitialAdShowResult) -> Unit = {},
    )
}
