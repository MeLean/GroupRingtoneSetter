package com.milen.grounpringtonesetter.data.repos

import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.accounts.AccountId
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.Tracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface ContactsRepository {
    val labelsFlow: StateFlow<List<LabelItem>>

    suspend fun load()

    suspend fun setGroupRingtone(group: LabelItem, uriStr: String, fileName: String)
    fun deleteGroup(groupId: Long)

    fun getAllPhoneContacts(): List<Contact>

    fun createGroup(name: String)

    fun renameGroup(labelId: Long, newName: String)

    fun updateGroupMembers(
        groupId: Long,
        newSelected: List<Contact>,
        oldSelected: List<Contact>,
    )

    fun clearAllRingtones()
}


internal class ContactsRepositoryImpl(
    private val helper: ContactsHelper,
    private val tracker: Tracker,
    private val prefs: EncryptedPreferencesHelper,
    private val accountsProvider: () -> AccountId?,
) : ContactsRepository {

    private val lock = Mutex()
    private val _labels = MutableStateFlow<List<LabelItem>>(emptyList())
    override val labelsFlow: StateFlow<List<LabelItem>> = _labels

    override suspend fun load() {
        return lock.withLock {
            val selected = accountsProvider()
            val fresh = if (selected == null) {
                tracker.trackError(RuntimeException("The contacts are queried with no accounts selected"))
                helper.getAllLabelItems()
            } else {
                helper.getAllLabelItemsForAccounts(selected)
            }

            _labels.update { fresh }
        }
    }

    override suspend fun setGroupRingtone(
        group: LabelItem,
        uriStr: String,
        fileName: String,
    ) {
        tracker.trackEvent("set_group_ringtone_in_repo")

        if (fileName.isNotBlank()) prefs.saveString(uriStr, fileName)

        helper.setRingtoneToLabelContacts(
            labelContacts = group.contacts,
            newRingtoneUriStr = uriStr
        )

        updateGroupRingtone(group.id, uriStr, fileName)
    }

    override fun deleteGroup(groupId: Long) {
        tracker.trackEvent("delete_group: $groupId")
        helper.deleteLabel(groupId)

        val updated = _labels.value.filter { it.id != groupId }
        _labels.update { updated }
    }

    override fun createGroup(name: String) {
        tracker.trackEvent("create_group")
        val result = helper.createLabel(name, accountsProvider())
        if (result == null) {
            throw Throwable()
        }

        val updated = _labels.value + result
        _labels.update { updated }
    }

    override fun renameGroup(labelId: Long, newName: String) {
        tracker.trackEvent("rename_group")
        helper.updateLabelName(labelId, newName)
        val updated = _labels.value.map { g ->
            if (g.id == labelId) g.copy(groupName = newName) else g
        }
        _labels.update { updated }
    }

    override fun updateGroupMembers(
        groupId: Long,
        newSelected: List<Contact>,
        oldSelected: List<Contact>,
    ) {
        helper.addAllContactsToLabel(groupId, newSelected)
        val excluded = oldSelected.filterNot { o -> newSelected.any { it.id == o.id } }
        helper.removeAllContactsFromLabel(groupId, excluded)
        tracker.trackEvent("update_group_members")

        val updated = _labels.value.map { label ->
            if (label.id == groupId) {
                label.copy(contacts = newSelected)
            } else {
                label
            }
        }

        _labels.update { updated }
    }

    override fun clearAllRingtones() {
        helper.clearAllRingtoneUris()
        tracker.trackEvent("clear_all_ringtones")
        val updated =
            _labels.value.map { g -> g.copy(ringtoneUriList = emptyList(), ringtoneFileName = "") }
        _labels.update { updated }
    }


    override fun getAllPhoneContacts(): List<Contact> =
        helper.getAllPhoneContacts() // TODO GET CONTACTS ONLY FOR THE CURREND ACCOUNT accountprovider


    private fun updateGroupRingtone(
        groupId: Long,
        uriStr: String,
        fileName: String,
    ) {
        val updated = _labels.value.map { g ->
            if (g.id == groupId) {
                g.copy(
                    ringtoneUriList = listOf(uriStr),
                    ringtoneFileName = fileName,
                    contacts = g.contacts.map { c -> c.copy(ringtoneUriStr = uriStr) }
                )
            } else g
        }

        _labels.update { updated }
    }
}