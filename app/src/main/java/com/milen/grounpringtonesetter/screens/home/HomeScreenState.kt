package com.milen.grounpringtonesetter.screens.home

import com.milen.grounpringtonesetter.data.LabelItem

data class HomeScreenState(
    val labelItems: List<LabelItem> = mutableListOf(),
    val isLoading: Boolean = true,
    val arePermissionsGranted: Boolean = true,
    val scrollToBottom: Boolean = false,
)
