package com.milen.grounpringtonesetter.ui.picker

import androidx.annotation.StringRes
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.ui.picker.data.PickerResultData


internal data class PickerScreenState(
    @param:StringRes val titleId: Int = R.string.loading,
    val isLoading: Boolean = true,
    val pikerResultData: PickerResultData? = null,
    val shouldPop: Boolean = false,
    val allContacts: List<Contact> = emptyList(),
)

internal sealed interface PickerEvent {
    data object Close : PickerEvent
    data object DoneDialog : PickerEvent
    data class ShowErrorById(@param:StringRes val strRes: Int) : PickerEvent
    data class ShowInfoText(@param:StringRes val strRes: Int) : PickerEvent
    data class ShowErrorText(val message: String?) : PickerEvent
}