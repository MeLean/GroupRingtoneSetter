package com.milen.grounpringtonesetter.actions

import android.accounts.Account
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.Tracker

internal class GroupActions(
    private val contacts: ContactsHelper,
    private val tracker: Tracker,
) {
    // Groups
    suspend fun renameGroup(labelId: Long, newName: String) {
        contacts.updateLabelName(labelId, newName)
        tracker.trackEvent("rename_group")
    }

    suspend fun createGroup(name: String, account: Account?): LabelItem? {
        val item = contacts.createLabel(name, account)
        tracker.trackEvent("create_group")
        return item
    }

    suspend fun deleteGroup(labelId: Long) {
        contacts.deleteLabel(labelId)
        tracker.trackEvent("delete_group")
    }

    suspend fun updateGroupMembers(
        groupId: Long,
        newSelected: List<Contact>,
        oldSelected: List<Contact>,
    ) {
        contacts.addAllContactsToLabel(groupId, newSelected)
        val excluded = oldSelected.filterNot { o -> newSelected.any { it.id == o.id } }
        contacts.removeAllContactsFromLabel(groupId, excluded)
        tracker.trackEvent("update_group_members")
    }

    // Ringtones
    suspend fun setGroupRingtone(contactsInGroup: List<Contact>, mediaStoreUriString: String) {
        contacts.setRingtoneToLabelContacts(
            labelContacts = contactsInGroup,
            newRingtoneUriStr = mediaStoreUriString
        )
        tracker.trackEvent("set_group_ringtone")
    }

    suspend fun clearAllRingtones() {
        contacts.clearAllRingtoneUris()
        tracker.trackEvent("clear_all_ringtones")
    }
}
