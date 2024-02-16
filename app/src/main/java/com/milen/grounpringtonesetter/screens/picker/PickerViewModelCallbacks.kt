package com.milen.grounpringtonesetter.screens.picker

import kotlinx.coroutines.flow.StateFlow

data class PickerViewModelCallbacks(
    val uiState: StateFlow<PickerScreenState>,
)
