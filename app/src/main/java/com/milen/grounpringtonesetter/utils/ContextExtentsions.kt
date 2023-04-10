package com.milen.grounpringtonesetter.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback

fun Context.loadRewardAd(
    adId: String,
    onDone: (RewardedInterstitialAd?) -> Unit
) {
    RewardedInterstitialAd.load(this, adId,
        AdRequest.Builder().build(), object : RewardedInterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedInterstitialAd) {
                onDone(ad)
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                onDone(null)
            }
        }
    )
}

fun Context.hasInternetConnection(): Boolean =
    with(getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager) {
        this?.activeNetwork?.let {
            getNetworkCapabilities(it)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    } ?: false