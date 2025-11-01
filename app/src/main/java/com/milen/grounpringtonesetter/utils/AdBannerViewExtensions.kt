package com.milen.grounpringtonesetter.utils

import androidx.core.view.isVisible
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.ui.ads.AdBannerView

internal fun AdBannerView.manageVisibility(st: EntitlementState) {
    when (st) {
        EntitlementState.NOT_OWNED -> isVisible = true
        EntitlementState.PENDING -> isVisible = false
        EntitlementState.UNKNOWN -> isVisible = true
        EntitlementState.OWNED -> this.isVisible = false.also { destroyBanner() }
    }
}