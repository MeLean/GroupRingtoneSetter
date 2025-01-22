package com.milen.grounpringtonesetter.screens.picker.data

import android.accounts.Account
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem

sealed interface PickerResultData {
    data class GroupNameChange(
        val labelItem: LabelItem,
        val newGroupName: String? = null,
    ) : PickerResultData

    data class ManageGroups(
        val groupName: String = "",
        val accountLists: List<Account>,
        val pickedAccount: Account?,
    ) : PickerResultData

    data class ManageGroupContacts(
        val group: LabelItem,
        val selectedContacts: List<Contact> = emptyList(),
        val allContacts: List<Contact>,
    ) : PickerResultData

    data object Canceled : PickerResultData
}