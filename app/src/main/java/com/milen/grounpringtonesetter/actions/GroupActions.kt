package com.milen.grounpringtonesetter.actions

import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.accounts.AccountRepository
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.Tracker

internal class GroupActions(
    private val contacts: ContactsHelper,
    private val tracker: Tracker,
    private val accountRepo: AccountRepository,
) {
    // Groups
    fun renameGroup(labelId: Long, newName: String) {
        contacts.updateLabelName(labelId, newName)
        tracker.trackEvent("rename_group")
    }

    fun createGroup(name: String): LabelItem? {
        var item: LabelItem? = null

        accountRepo.selected.value?.let {
            item = contacts.createLabel(
                labelName = name,
                accountId = it
            )
            tracker.trackEvent("create_group")
        } ?: tracker.trackError(RuntimeException("createGroup in account id null!"))

        return item
    }

    fun deleteGroup(labelId: Long) {
        contacts.deleteLabel(labelId)
        tracker.trackEvent("delete_group")
    }

    fun updateGroupMembers(
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

    fun clearAllRingtones() {
        contacts.clearAllRingtoneUris()
        tracker.trackEvent("clear_all_ringtones")
    }
}
