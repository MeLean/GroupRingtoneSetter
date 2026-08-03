package com.milen.grounpringtonesetter.customviews.ui.ads

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/** App-wide ads and consent boundary used by UI and ad renderers. */
internal interface AdsGateway {
    val interstitialAdPolicy: InterstitialAdPolicy
    val canLoadAds: StateFlow<Boolean>
    val isPrivacyOptionsRequired: StateFlow<Boolean>

    fun initialize()

    fun requestConsent(activity: Activity)

    fun showPrivacyOptionsForm(activity: Activity)

    fun openAdInspector(activity: Activity)
}
