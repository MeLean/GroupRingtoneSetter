package com.milen.grounpringtonesetter.screens.picker.data

import com.milen.grounpringtonesetter.data.Contact

sealed interface PickerResultData {
    data class GroupNameChanged(
        val groupId: Long,
        val oldName: String,
        val newGroupName: String
    ) : PickerResultData

    data class ContactsGroupChanged(
        val groupId: Long,
        val includedContacts: List<Contact>,
        val excludedContacts: List<Contact>
    ) : PickerResultData

    object Canceled : PickerResultData
}