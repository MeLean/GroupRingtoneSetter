package com.milen.grounpringtonesetter.screens.home

import com.milen.grounpringtonesetter.data.GroupItem

data class HomeScreenState(
    var id: Long = System.currentTimeMillis(),
    val groupItems: MutableList<GroupItem> = mutableListOf(),
    var isLoading: Boolean = true,
    val areLabelsFetched: Boolean = false,
    val isAllDone: Boolean = false,
    val shouldShowAd: Boolean = false
)
