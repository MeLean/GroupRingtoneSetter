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
import com.milen.grounpringtonesetter.billing.EntitlementState
import java.lang.ref.WeakReference

internal enum class InterstitialAdShowResult {
    SHOWN,
    SKIPPED,
    LOAD_FAILED,
    SHOW_FAILED,
}

internal fun interstitialResultWhenActivityUnavailable(): InterstitialAdShowResult =
    InterstitialAdShowResult.SKIPPED

internal class AdLoadingHelper(
    activity: Activity,
    private val placement: String = activity::class.java.simpleName,
) : InterstitialAdGateway {
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
    private var isHostActive = true
    private var activeShowCompletion: ((InterstitialAdShowResult) -> Unit)? = null

    override fun updateActivity(activity: Activity) {
        if (activityRef.get() !== activity) activeShowCompletion?.invoke(InterstitialAdShowResult.SKIPPED)
        activityRef = WeakReference(activity)
        isHostActive = true
    }

    override fun setHostActive(active: Boolean) {
        isHostActive = active
    }

    override fun release() {
        isHostActive = false
        activityRef.clear()
        finishLoading(false)
        activeShowCompletion?.invoke(InterstitialAdShowResult.SKIPPED)
        if (!isAdEligible()) clearCachedInterstitial()
        // Inventory and the in-flight request belong to the application/placement, not this view.
    }

    fun currentActivityOrNull(): Activity? =
        activityRef.get()?.takeUnless { it.isDestroyed || it.isFinishing }

    fun loadInterstitialAd(
        onAdLoadingFinished: (Boolean) -> Unit = {},
    ) {
        if (!isAdEligible() || !isHostActive) {
            if (!isAdEligible()) clearCachedInterstitial()
            onAdLoadingFinished(false)
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        clearExpiredInterstitialIfNeeded(nowMs)
        if (interstitialAd != null) {
            logDebug("cache reused")
            onAdLoadingFinished(true)
            return
        }

        if (isLoadingInterstitialAd) {
            loadCallbacks += onAdLoadingFinished
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
        isLoadingInterstitialAd = true
        val adRequest = AdRequest.Builder().build()
        val adUnitId = app.getString(R.string.admob_interstitial_unit_id)
        AdDiagnostics.logDebugEvent(
            format = "interstitial",
            placement = placement,
            message = "requesting unit=$adUnitId"
        )
        var requestCompleted = false
        runCatching {
            InterstitialAd.load(
                app,
                adUnitId,
                adRequest,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        if (requestCompleted) return
                        requestCompleted = true
                        isLoadingInterstitialAd = false
                        policy.onLoadSucceeded()
                        if (!isAdEligible()) {
                            logDebug("loaded ad discarded: eligibility changed")
                            finishLoading(false)
                            return
                        }
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
                        finishLoading(true)
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        if (requestCompleted) return
                        requestCompleted = true
                        isLoadingInterstitialAd = false
                        clearCachedInterstitial()
                        policy.onLoadFailed(SystemClock.elapsedRealtime())
                        AdDiagnostics.logLoadFailure(
                            format = "interstitial",
                            placement = placement,
                            adUnitId = adUnitId,
                            error = adError
                        )
                        finishLoading(false)
                    }
                },
            )
        }.onFailure { throwable ->
            if (requestCompleted) return@onFailure
            requestCompleted = true
            isLoadingInterstitialAd = false
            policy.onLoadFailed(SystemClock.elapsedRealtime())
            AdDiagnostics.trackUnexpectedState(
                tracker = tracker,
                format = "interstitial",
                placement = placement,
                stage = "load",
                reason = "interstitial_load_threw",
                throwable = throwable,
            )
            finishLoading(false)
        }
    }

    override fun preloadInterstitialAd() {
        loadInterstitialAd()
    }

    override fun showInterstitialAd(
        onAdLoadingFinished: (InterstitialAdShowResult) -> Unit,
    ) {
        if (!isAdEligible()) {
            clearCachedInterstitial()
            logDebug("show skipped consent, SDK readiness or entitlement")
            onAdLoadingFinished(InterstitialAdShowResult.SKIPPED)
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        clearExpiredInterstitialIfNeeded(nowMs)

        if (!isHostActive || !policy.canShow()) {
            logDebug("show skipped: host inactive or fullscreen already showing")
            onAdLoadingFinished(InterstitialAdShowResult.SKIPPED)
            return
        }
        if (interstitialAd == null) {
            // An operation never waits on a network request or displays later on another screen.
            logDebug("show skipped: cache unavailable")
            preloadInterstitialAd()
            onAdLoadingFinished(InterstitialAdShowResult.SKIPPED)
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
            onAdLoadingFinished(interstitialResultWhenActivityUnavailable())
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

        val ad = interstitialAd ?: run {
            policy.onFullscreenAdClosed()
            onAdLoadingFinished(InterstitialAdShowResult.SKIPPED)
            return
        }
        // Consume this ad before invoking the SDK so reentrant calls cannot reuse it.
        interstitialAd = null
        interstitialLoadedAtMs = 0L
        var completed = false
        var clientCallback: ((InterstitialAdShowResult) -> Unit)? = onAdLoadingFinished
        fun completeClient(result: InterstitialAdShowResult) {
            activeShowCompletion = null
            val callback = clientCallback
            clientCallback = null
            callback?.invoke(result)
        }
        fun complete(result: InterstitialAdShowResult) {
            if (completed) return
            completed = true
            ad.fullScreenContentCallback = null
            policy.onFullscreenAdClosed()
            completeClient(result)
        }
        // Releasing a screen resolves its operation, but the SDK still owns the full-screen
        // lease until dismissal/failure. Clearing that callback early could unlock two ads.
        activeShowCompletion = ::completeClient
        logDebug("show attempted")
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                AdDiagnostics.logDebugEvent(
                    format = "interstitial",
                    placement = placement,
                    message = "shown unit=${ad.adUnitId}"
                )
            }

            override fun onAdImpression() {
                if (completed) return
                AdDiagnostics.logDebugEvent(
                    format = "interstitial",
                    placement = placement,
                    message = "impression unit=${ad.adUnitId}"
                )
            }

            override fun onAdClicked() {
                AdDiagnostics.logDebugEvent(
                    format = "interstitial",
                    placement = placement,
                    message = "clicked unit=${ad.adUnitId}"
                )
            }

            override fun onAdDismissedFullScreenContent() {
                if (completed) return
                logDebug("dismissed")
                complete(InterstitialAdShowResult.SHOWN)
                preloadInterstitialAd()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                if (completed) return
                val adUnitId = ad.adUnitId
                AdDiagnostics.logShowFailure(
                    format = "interstitial",
                    placement = placement,
                    adUnitId = adUnitId,
                    error = adError
                )
                complete(InterstitialAdShowResult.SHOW_FAILED)
                preloadInterstitialAd()
            }
        }

        runCatching {
            ad.show(activity)
        }.onFailure { throwable ->
            if (completed) return@onFailure
            AdDiagnostics.trackUnexpectedState(
                tracker = tracker,
                format = "interstitial",
                placement = placement,
                stage = "show",
                reason = "interstitial_show_threw",
                throwable = throwable
            )
            complete(InterstitialAdShowResult.SHOW_FAILED)
            preloadInterstitialAd()
        }
    }

    private fun isAdEligible(): Boolean =
        adsManager.canLoadAds.value && app.billingManager.state.value == EntitlementState.NOT_OWNED

    private fun logDebug(message: String) =
        AdDiagnostics.logDebugEvent("interstitial", placement, message)

    private fun finishLoading(success: Boolean) {
        val callbacks = loadCallbacks.toList()
        loadCallbacks.clear()
        callbacks.forEach { it(success) }
    }

    private fun clearCachedInterstitial() {
        interstitialAd?.fullScreenContentCallback = null
        interstitialAd?.onPaidEventListener = null
        interstitialAd = null
        interstitialLoadedAtMs = 0L
    }

    private fun clearExpiredInterstitialIfNeeded(nowMs: Long): Boolean {
        val hasInterstitial = interstitialAd != null
        val isExpired =
            hasInterstitial &&
                    nowMs - interstitialLoadedAtMs >= INTERSTITIAL_EXPIRATION_MS
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
