package com.milen.grounpringtonesetter.ui.screenstate

import com.milen.grounpringtonesetter.data.GroupItem

data class LabelListScreenState(
    var id: Long = System.currentTimeMillis(),
    val groupItems: MutableList<GroupItem> = mutableListOf(),
    var isLoading: Boolean = true,
    val areLabelsFetched: Boolean = false,
    val isAllDone: Boolean = false
)
