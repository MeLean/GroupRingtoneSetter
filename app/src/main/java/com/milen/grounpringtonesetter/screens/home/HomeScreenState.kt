package com.milen.grounpringtonesetter.screens.home

import com.google.android.gms.ads.interstitial.InterstitialAd
import com.milen.grounpringtonesetter.data.GroupItem

data class HomeScreenState(
    val id: Long = System.currentTimeMillis(),
    val groupItems: List<GroupItem> = mutableListOf(),
    var isLoading: Boolean = true,
    val areLabelsFetched: Boolean = false,
    val isAllDone: Boolean = false,
    val onDoneAd: InterstitialAd? = null
)
