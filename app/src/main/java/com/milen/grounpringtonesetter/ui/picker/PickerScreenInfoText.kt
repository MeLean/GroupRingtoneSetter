package com.milen.grounpringtonesetter.ui.picker

import androidx.annotation.StringRes
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.ui.picker.data.PickerMode

internal object PickerScreenInfoText {
    @StringRes
    fun getMessageResId(mode: PickerMode): Int = when (mode) {
        PickerMode.CREATE -> R.string.picker_create_info_text
        PickerMode.RENAME -> R.string.picker_rename_info_text
        PickerMode.MANAGE -> R.string.picker_manage_info_text
    }
}
