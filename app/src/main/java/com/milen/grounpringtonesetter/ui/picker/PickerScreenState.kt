package com.milen.grounpringtonesetter.ui.picker

import androidx.annotation.StringRes
import com.milen.grounpringtonesetter.ui.picker.data.PickerResultData


internal data class PickerScreenState(
    @param:StringRes val titleId: Int,
    val isLoading: Boolean = true,
    val pikerResultData: PickerResultData? = null,
    val shouldPop: Boolean = false,
)