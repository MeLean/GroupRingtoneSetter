package com.milen.grounpringtonesetter.utils

import androidx.core.view.isVisible
import com.milen.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.ui.ads.AdBannerView

internal fun AdBannerView.manageVisibility(st: EntitlementState) {
    when (st) {
        EntitlementState.NOT_OWNED -> isVisible = true
        EntitlementState.UNKNOWN -> isVisible = false
        EntitlementState.OWNED -> this.isVisible = false.also { destroyBanner() }
    }
}