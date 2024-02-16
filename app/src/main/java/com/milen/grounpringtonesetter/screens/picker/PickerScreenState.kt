package com.milen.grounpringtonesetter.screens.picker

import com.milen.grounpringtonesetter.screens.picker.data.PickerData


data class PickerScreenState(
    val id: Long = System.currentTimeMillis(),
    val isLoading: Boolean = true,
    val pikerData: PickerData? = null
)