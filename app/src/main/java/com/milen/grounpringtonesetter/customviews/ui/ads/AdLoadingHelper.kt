package com.milen.grounpringtonesetter.customviews.ui.ads

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.milen.grounpringtonesetter.BuildConfig
import com.milen.grounpringtonesetter.R
import java.lang.ref.WeakReference

internal enum class InterstitialAdShowResult {
    SHOWN,
    LOAD_FAILED,
    SHOW_FAILED,
}

internal class AdLoadingHelper(activity: Activity) {
    private var interstitialAd: InterstitialAd? = null
    private var isLoadingInterstitialAd = false
    private val loadCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var activityRef = WeakReference(activity)

    fun updateActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun currentActivityOrNull(): Activity? =
        activityRef.get()?.takeUnless { it.isDestroyed || it.isFinishing }

    fun loadInterstitialAd(
        onAdLoadingFinished: (Boolean) -> Unit = {},
    ) {
        if (interstitialAd != null) {
            onAdLoadingFinished(true)
            return
        }

        val activity = currentActivityOrNull()
        if (activity == null) {
            onAdLoadingFinished(false)
            return
        }

        loadCallbacks += onAdLoadingFinished
        if (isLoadingInterstitialAd) return

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
                    val callbacks = loadCallbacks.toList()
                    loadCallbacks.clear()
                    callbacks.forEach { it(true) }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    isLoadingInterstitialAd = false
                    val callbacks = loadCallbacks.toList()
                    loadCallbacks.clear()
                    callbacks.forEach { it(false) }
                }
            })
    }

    fun showInterstitialAd(
        onAdLoadingFinished: (InterstitialAdShowResult) -> Unit = {},
    ) {
        if (interstitialAd == null) {
            loadInterstitialAd(
                onAdLoadingFinished = { isSuccessfulLoaded ->
                    if (isSuccessfulLoaded) {
                        showInterstitialAd(onAdLoadingFinished)
                    } else {
                        onAdLoadingFinished(InterstitialAdShowResult.LOAD_FAILED)
                    }
                }
            )

            return
        }

        val activity = currentActivityOrNull()
        if (activity == null) {
            interstitialAd = null
            onAdLoadingFinished(InterstitialAdShowResult.SHOW_FAILED)
            return
        }

        interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                onAdLoadingFinished(InterstitialAdShowResult.SHOWN)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                interstitialAd = null
                onAdLoadingFinished(InterstitialAdShowResult.SHOW_FAILED)
            }
        }

        interstitialAd?.show(activity)
    }
}

private fun Activity.getInterstitialAdId(): String =
    if (BuildConfig.DEBUG) getString(R.string.ad_id_interstitial_debug)
    else getString(R.string.ad_id_interstitial)
