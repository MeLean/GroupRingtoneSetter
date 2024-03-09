package com.milen.grounpringtonesetter.screens.picker.data

import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.GroupItem

sealed interface PickerResultData {
    data class GroupNameChange(
        val groupItem: GroupItem,
        val newGroupName: String? = null
    ) : PickerResultData

    data class GroupSetName(
        val groupName: String = ""
    ) : PickerResultData

    data class ManageGroupContacts(
        val group: GroupItem,
        val selectedContacts: List<Contact> = emptyList(),
        val allContacts: List<Contact>
    ) : PickerResultData

    data object Canceled : PickerResultData
}