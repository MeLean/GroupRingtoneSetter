package com.milen.grounpringtonesetter.screens.home

import com.milen.grounpringtonesetter.data.GroupItem

data class HomeScreenState(
    val groupItems: List<GroupItem> = mutableListOf(),
    val isLoading: Boolean = true,
    val arePermissionsGranted: Boolean = true,
    val scrollToBottom: Boolean = false,
)
