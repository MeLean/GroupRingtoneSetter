package com.milen.grounpringtonesetter.customviews.ui.ads

import android.app.Activity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.milen.grounpringtonesetter.BuildConfig
import com.milen.grounpringtonesetter.R

class AdLoadingHelper(private val activity: Activity) {
    private var interstitialAd: InterstitialAd? = null
    private var isLoadingInterstitialAd = false

    fun loadInterstitialAd(
        onAdLoadingFinished: (Boolean) -> Unit = {},
    ) {
        if (interstitialAd != null || isLoadingInterstitialAd) return

        isLoadingInterstitialAd = true
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            activity,
            activity.getInterstitialAdId(),
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    isLoadingInterstitialAd = false
                    interstitialAd = ad
                    onAdLoadingFinished(true)
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    isLoadingInterstitialAd = false
                    onAdLoadingFinished(false)
                }
            })
    }

    fun showInterstitialAd(onAdLoadingFinished: (Boolean) -> Unit = {}) {
        if (interstitialAd == null) {
            loadInterstitialAd(
                onAdLoadingFinished = { isSuccessfulLoaded ->
                    if (isSuccessfulLoaded) {
                        showInterstitialAd(onAdLoadingFinished)
                    }
                }
            )

            return
        }

        interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                onAdLoadingFinished(false)
                interstitialAd = null
            }
        }

        interstitialAd?.show(activity)
    }
}

private fun Activity.getInterstitialAdId(): String =
    if (BuildConfig.DEBUG) getString(R.string.ad_id_interstitial_debug)
    else getString(R.string.ad_id_interstitial)