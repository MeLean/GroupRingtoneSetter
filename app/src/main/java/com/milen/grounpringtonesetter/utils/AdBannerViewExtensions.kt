package com.milen.grounpringtonesetter.utils

import androidx.core.view.isVisible
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.ui.ads.AdBannerView

internal fun AdBannerView.manageVisibility(
    entitlement: EntitlementState,
    canLoadAds: Boolean,
) {
    val shouldShowBanner = entitlement == EntitlementState.NOT_OWNED && canLoadAds
    isVisible = shouldShowBanner
    setAdsEnabled(shouldShowBanner)
}
