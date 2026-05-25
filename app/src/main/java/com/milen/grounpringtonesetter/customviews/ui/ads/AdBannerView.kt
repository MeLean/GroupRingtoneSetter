package com.milen.grounpringtonesetter.customviews.ui.ads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.OnPaidEventListener
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.R

internal class AdBannerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private companion object {
        private val RETRY_DELAYS_MS = longArrayOf(30_000L, 60_000L, 120_000L)
    }

    private val adsManager = (context.applicationContext as App).adsManager
    private val tracker = (context.applicationContext as App).tracker
    private val retryHandler = Handler(Looper.getMainLooper())

    private var bannerAdView: AdView? = null
    private var isBannerEnabled = false
    private var isBannerLoading = false
    private var bannerLoaded = false
    private var placement: String = "unknown"
    private var bannerRetryAttempt = 0
    private var pendingRetry: Runnable? = null

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        destroyBanner()
    }

    fun destroyBanner() {
        cancelPendingRetry()
        bannerLoaded = false
        isBannerLoading = false
        bannerRetryAttempt = 0
        bannerAdView?.destroy()
        bannerAdView = null
        removeAllViews()
    }

    fun setPlacement(placement: String) {
        this.placement = placement
    }

    fun setAdsEnabled(isEnabled: Boolean) {
        if (isBannerEnabled == isEnabled) return
        isBannerEnabled = isEnabled
        if (isEnabled) {
            maybeLoadBanner()
        } else {
            destroyBanner()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        maybeLoadBanner()
    }

    private fun maybeLoadBanner() {
        if (!isBannerRequestAllowed()) return
        if (bannerLoaded || isBannerLoading) return

        if (width == 0) {
            doOnLayout { maybeLoadBanner() }
            return
        }

        val adView = AdView(context).apply {
            adUnitId = context.getString(R.string.admob_banner_unit_id)
            setAdSize(resolveAdaptiveSize())
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    cancelPendingRetry()
                    isBannerLoading = false
                    bannerLoaded = true
                    bannerRetryAttempt = 0
                    AdDiagnostics.logDebugEvent(
                        format = "banner",
                        placement = placement,
                        message = "loaded unit=$adUnitId responseId=${responseInfo?.responseId.orEmpty()}"
                    )
                }

                override fun onAdFailedToLoad(loadAdError: com.google.android.gms.ads.LoadAdError) {
                    isBannerLoading = false
                    bannerLoaded = false
                    AdDiagnostics.logLoadFailure(
                        format = "banner",
                        placement = placement,
                        adUnitId = adUnitId,
                        error = loadAdError
                    )
                    scheduleBannerRetry()
                }

                override fun onAdImpression() {
                    AdDiagnostics.logDebugEvent(
                        format = "banner",
                        placement = placement,
                        message = "impression unit=$adUnitId"
                    )
                }

                override fun onAdClicked() {
                    AdDiagnostics.logDebugEvent(
                        format = "banner",
                        placement = placement,
                        message = "clicked unit=$adUnitId"
                    )
                }
            }
            onPaidEventListener = OnPaidEventListener { adValue ->
                AdDiagnostics.trackPaidEvent(
                    tracker = tracker,
                    format = "banner",
                    placement = placement,
                    adUnitId = adUnitId,
                    adValue = adValue,
                    responseInfo = responseInfo
                )
            }
        }

        replaceBannerView(adView)
        isBannerLoading = true
        AdDiagnostics.logDebugEvent(
            format = "banner",
            placement = placement,
            message = "requesting unit=${adView.adUnitId}"
        )
        runCatching {
            adView.loadAd(AdRequest.Builder().build())
        }.onFailure { throwable ->
            isBannerLoading = false
            bannerLoaded = false
            AdDiagnostics.trackUnexpectedState(
                tracker = tracker,
                format = "banner",
                placement = placement,
                stage = "load",
                reason = "banner_load_threw",
                throwable = throwable
            )
            scheduleBannerRetry()
        }
    }

    private fun isBannerRequestAllowed(): Boolean {
        if (!isAttachedToWindow) return false
        if (!isBannerEnabled) return false
        if (!adsManager.canLoadAds.value) return false
        if (visibility != VISIBLE) return false
        if (!isShown) return false
        return true
    }

    private fun scheduleBannerRetry() {
        if (!isBannerRequestAllowed()) return
        if (pendingRetry != null) return

        val retryDelayMs = RETRY_DELAYS_MS[bannerRetryAttempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        bannerRetryAttempt += 1
        pendingRetry = Runnable {
            pendingRetry = null
            maybeLoadBanner()
        }.also { runnable ->
            retryHandler.postDelayed(runnable, retryDelayMs)
        }
        AdDiagnostics.logDebugEvent(
            format = "banner",
            placement = placement,
            message = "retry scheduled delayMs=$retryDelayMs attempt=$bannerRetryAttempt"
        )
    }

    private fun cancelPendingRetry() {
        pendingRetry?.let(retryHandler::removeCallbacks)
        pendingRetry = null
    }

    private fun replaceBannerView(adView: AdView) {
        val previousBannerView = bannerAdView
        removeAllViews()
        if (previousBannerView !== adView) {
            previousBannerView?.destroy()
        }
        bannerAdView = adView
        addView(adView)
    }

    private fun resolveAdaptiveSize(): AdSize {
        val displayMetrics = resources.displayMetrics
        val adWidthPixels = width.takeIf { it > 0 } ?: displayMetrics.widthPixels
        val adWidthDp = (adWidthPixels / displayMetrics.density).toInt().coerceAtLeast(1)
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidthDp)
    }
}
