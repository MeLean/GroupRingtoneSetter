package com.milen.grounpringtonesetter.customviews.ui.ads

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.milen.grounpringtonesetter.R

class AdBannerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var bannerAdView: AdView = AdView(context)

    init {
        with(bannerAdView) {
            setAdSize(AdSize.BANNER)
            adUnitId = context.getString(R.string.ad_id_banner)
            loadAd(AdRequest.Builder().build())
        }

        addView(bannerAdView)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bannerAdView.destroy()
    }
}