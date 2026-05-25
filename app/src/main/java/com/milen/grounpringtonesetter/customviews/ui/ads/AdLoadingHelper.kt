package com.milen.grounpringtonesetter.customviews.ui.ads

import android.app.Activity
import android.os.SystemClock
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.R
import java.lang.ref.WeakReference

internal enum class InterstitialAdShowResult {
    SHOWN,
    SKIPPED,
    LOAD_FAILED,
    SHOW_FAILED,
}

internal class AdLoadingHelper(
    activity: Activity,
    private val placement: String = activity::class.java.simpleName,
) {
    private companion object {
        private const val INTERSTITIAL_EXPIRATION_MS = 55 * 60 * 1000L
    }

    private val app = activity.application as App
    private val adsManager = app.adsManager
    private val tracker = app.tracker
    private val policy = adsManager.interstitialAdPolicy
    private var interstitialAd: InterstitialAd? = null
    private var interstitialLoadedAtMs = 0L
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

        if (!adsManager.canLoadAds.value) {
            onAdLoadingFinished(false)
            return
        }

        val activity = currentActivityOrNull()
        if (activity == null) {
            AdDiagnostics.trackUnexpectedState(
                tracker = tracker,
                format = "interstitial",
                placement = placement,
                stage = "load",
                reason = "activity_unavailable"
            )
            onAdLoadingFinished(false)
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        if (!policy.canLoad(nowMs)) {
            onAdLoadingFinished(false)
            AdDiagnostics.logDebugEvent(
                format = "interstitial",
                placement = placement,
                message = "load skipped backoffUntil=${policy.nextAllowedLoadAtMs()}"
            )
            return
        }

        loadCallbacks += onAdLoadingFinished
        if (isLoadingInterstitialAd) return

        isLoadingInterstitialAd = true
        val adRequest = AdRequest.Builder().build()
        val adUnitId = activity.getString(R.string.admob_interstitial_unit_id)
        AdDiagnostics.logDebugEvent(
            format = "interstitial",
            placement = placement,
            message = "requesting unit=$adUnitId"
        )
        InterstitialAd.load(
            activity,
            adUnitId,
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    isLoadingInterstitialAd = false
                    policy.onLoadSucceeded()
                    interstitialAd = ad
                    interstitialLoadedAtMs = SystemClock.elapsedRealtime()
                    ad.onPaidEventListener = OnPaidEventListener { adValue ->
                        AdDiagnostics.trackPaidEvent(
                            tracker = tracker,
                            format = "interstitial",
                            placement = placement,
                            adUnitId = ad.adUnitId,
                            adValue = adValue,
                            responseInfo = ad.responseInfo
                        )
                    }
                    AdDiagnostics.logDebugEvent(
                        format = "interstitial",
                        placement = placement,
                        message = "loaded unit=${ad.adUnitId} responseId=${ad.responseInfo.responseId.orEmpty()}"
                    )
                    val callbacks = loadCallbacks.toList()
                    loadCallbacks.clear()
                    callbacks.forEach { it(true) }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    isLoadingInterstitialAd = false
                    clearCachedInterstitial()
                    policy.onLoadFailed(SystemClock.elapsedRealtime())
                    AdDiagnostics.logLoadFailure(
                        format = "interstitial",
                        placement = placement,
                        adUnitId = adUnitId,
                        error = adError
                    )
                    val callbacks = loadCallbacks.toList()
                    loadCallbacks.clear()
                    callbacks.forEach { it(false) }
                }
            })
    }

    fun preloadInterstitialAd() {
        loadInterstitialAd()
    }

    fun showInterstitialAd(
        onAdLoadingFinished: (InterstitialAdShowResult) -> Unit = {},
    ) {
        if (!adsManager.canLoadAds.value) {
            onAdLoadingFinished(InterstitialAdShowResult.SKIPPED)
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        clearExpiredInterstitialIfNeeded(nowMs)

        if (!policy.canShow(nowMs)) {
            AdDiagnostics.logDebugEvent(
                format = "interstitial",
                placement = placement,
                message = "show skipped due to frequency cap"
            )
            onAdLoadingFinished(InterstitialAdShowResult.SKIPPED)
            return
        }

        if (interstitialAd == null && !policy.canLoad(nowMs)) {
            AdDiagnostics.logDebugEvent(
                format = "interstitial",
                placement = placement,
                message = "show skipped load backoffUntil=${policy.nextAllowedLoadAtMs()}"
            )
            onAdLoadingFinished(InterstitialAdShowResult.SKIPPED)
            return
        }

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
            AdDiagnostics.trackUnexpectedState(
                tracker = tracker,
                format = "interstitial",
                placement = placement,
                stage = "show",
                reason = "activity_unavailable"
            )
            clearCachedInterstitial()
            onAdLoadingFinished(InterstitialAdShowResult.SHOW_FAILED)
            return
        }

        if (!policy.tryMarkFullscreenAdShowing()) {
            AdDiagnostics.trackUnexpectedState(
                tracker = tracker,
                format = "interstitial",
                placement = placement,
                stage = "show",
                reason = "fullscreen_state_already_showing"
            )
            onAdLoadingFinished(InterstitialAdShowResult.SKIPPED)
            return
        }

        interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                AdDiagnostics.logDebugEvent(
                    format = "interstitial",
                    placement = placement,
                    message = "shown unit=${interstitialAd?.adUnitId.orEmpty()}"
                )
            }

            override fun onAdImpression() {
                policy.onAdImpression(SystemClock.elapsedRealtime())
                AdDiagnostics.logDebugEvent(
                    format = "interstitial",
                    placement = placement,
                    message = "impression unit=${interstitialAd?.adUnitId.orEmpty()}"
                )
            }

            override fun onAdClicked() {
                AdDiagnostics.logDebugEvent(
                    format = "interstitial",
                    placement = placement,
                    message = "clicked unit=${interstitialAd?.adUnitId.orEmpty()}"
                )
            }

            override fun onAdDismissedFullScreenContent() {
                policy.onFullscreenAdClosed()
                clearCachedInterstitial()
                onAdLoadingFinished(InterstitialAdShowResult.SHOWN)
                if (adsManager.canLoadAds.value) {
                    preloadInterstitialAd()
                }
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                val adUnitId = interstitialAd?.adUnitId.orEmpty()
                AdDiagnostics.logShowFailure(
                    format = "interstitial",
                    placement = placement,
                    adUnitId = adUnitId,
                    error = adError
                )
                policy.onFullscreenAdClosed()
                clearCachedInterstitial()
                onAdLoadingFinished(InterstitialAdShowResult.SHOW_FAILED)
                if (adsManager.canLoadAds.value) {
                    preloadInterstitialAd()
                }
            }
        }

        runCatching {
            interstitialAd?.show(activity)
        }.onFailure { throwable ->
            policy.onFullscreenAdClosed()
            clearCachedInterstitial()
            AdDiagnostics.trackUnexpectedState(
                tracker = tracker,
                format = "interstitial",
                placement = placement,
                stage = "show",
                reason = "interstitial_show_threw",
                throwable = throwable
            )
            onAdLoadingFinished(InterstitialAdShowResult.SHOW_FAILED)
        }
    }

    private fun clearCachedInterstitial() {
        interstitialAd?.fullScreenContentCallback = null
        interstitialAd = null
        interstitialLoadedAtMs = 0L
    }

    private fun clearExpiredInterstitialIfNeeded(nowMs: Long): Boolean {
        val hasInterstitial = interstitialAd != null
        val isExpired =
            hasInterstitial &&
                    interstitialLoadedAtMs > 0L &&
                    nowMs - interstitialLoadedAtMs > INTERSTITIAL_EXPIRATION_MS
        if (!isExpired) return false

        AdDiagnostics.logDebugEvent(
            format = "interstitial",
            placement = placement,
            message = "expired cached ad ageMs=${nowMs - interstitialLoadedAtMs}"
        )
        clearCachedInterstitial()
        return true
    }
}
