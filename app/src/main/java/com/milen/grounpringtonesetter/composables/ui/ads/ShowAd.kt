package com.milen.grounpringtonesetter.composables.ui.ads

import android.app.Activity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import com.milen.grounpringtonesetter.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ShowAd(onAdLoaded: () -> Unit = {}, onDone: () -> Unit) {
    val maxTries = 3
    val triesToLoadState = remember { mutableStateOf(0) }
    val activity = LocalContext.current as Activity
    val adRequest = remember { AdRequest.Builder().build() }
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(triesToLoadState.value) {
        val loadJob = coroutineScope.launch {
            withContext(Dispatchers.Main) {
                if (triesToLoadState.value >= maxTries) {
                    onDone()
                } else {
                    try {
                        RewardedInterstitialAd.load(
                            activity,
                            activity.getString(R.string.ad_id_reward),
                            adRequest,
                            object : RewardedInterstitialAdLoadCallback() {
                                override fun onAdLoaded(ad: RewardedInterstitialAd) {
                                    coroutineScope.launch {
                                        ad.show(
                                            activity,
                                            object : OnUserEarnedRewardListener,
                                                FullScreenContentCallback() {
                                                override fun onUserEarnedReward(p0: RewardItem) {
                                                    onDone()
                                                }

                                                override fun onAdDismissedFullScreenContent() {
                                                    onDone()
                                                }
                                            })

                                        onAdLoaded()
                                    }
                                }

                                override fun onAdFailedToLoad(loadError: LoadAdError) {
                                    triesToLoadState.value++
                                }
                            })
                    } catch (e: Exception) {
                        triesToLoadState.value++
                    }
                }
            }
        }

        onDispose {
            loadJob.cancel()
        }
    }
}