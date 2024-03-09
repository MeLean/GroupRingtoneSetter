package com.milen.grounpringtonesetter.screens.picker

import androidx.annotation.StringRes
import com.milen.grounpringtonesetter.screens.picker.data.PickerResultData


data class PickerScreenState(
    @StringRes val titleId: Int,
    val isLoading: Boolean = true,
    val pikerResultData: PickerResultData? = null,
)