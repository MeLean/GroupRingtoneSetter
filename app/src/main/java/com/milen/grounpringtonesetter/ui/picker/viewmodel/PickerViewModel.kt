package com.milen.grounpringtonesetter.ui.picker.viewmodel

import android.accounts.Account
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.actions.GroupActions
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.cache.ContactsSnapshotStore
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.data.prefs.SelectedAccountsStore
import com.milen.grounpringtonesetter.ui.picker.PickerEvent
import com.milen.grounpringtonesetter.ui.picker.PickerScreenState
import com.milen.grounpringtonesetter.ui.picker.data.PickerResultData
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.Tracker
import com.milen.grounpringtonesetter.utils.launchOnIoResultInMain
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class PickerViewModel(
    private val contactsHelper: ContactsHelper,
    private val actions: GroupActions,
    private val tracker: Tracker,
    private val encryptedPrefs: EncryptedPreferencesHelper,
    private val contactsStore: ContactsSnapshotStore = ContactsSnapshotStore,
) : ViewModel() {

    private val _state = MutableStateFlow(PickerScreenState(titleId = R.string.loading))
    val state: StateFlow<PickerScreenState> = _state

    private val _events = Channel<PickerEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private fun accountsKey(): String =
        SelectedAccountsStore.accountsKeyOrAll(encryptedPrefs)

    /** Read fresh items from provider and persist snapshot; show Done dialog afterwards. */
    private fun rebuildSnapshotFromProviderThenNotify() {
        _state.update { it.copy(isLoading = true) }
        launchOnIoResultInMain(
            work = {
                val fresh: List<LabelItem> = contactsHelper.getAllLabelItems()
                contactsStore.write(
                    prefs = encryptedPrefs,
                    accountsKey = accountsKey(),
                    items = fresh
                )
                true
            },
            onError = ::handleError,
            onSuccess = {
                _state.update { it.copy(isLoading = false) }
                viewModelScope.launch { _events.send(PickerEvent.DoneDialog) }
            }
        )
    }

    // ---- Screen starters ----

    fun startRename(group: LabelItem) {
        tracker.trackEvent("Picker_startRename")
        _state.update {
            PickerScreenState(
                isLoading = false,
                titleId = R.string.edit_group_name,
                pikerResultData = PickerResultData.GroupNameChange(labelItem = group)
            )
        }
    }

    fun startManageContacts(group: LabelItem) {
        tracker.trackEvent("Picker_startManageContacts")
        _state.update { it.copy(isLoading = true) }
        launchOnIoResultInMain(
            work = { contactsHelper.getAllPhoneContacts() },
            onError = ::handleError,
            onSuccess = { all ->
                _state.update {
                    PickerScreenState(
                        isLoading = false,
                        titleId = R.string.manage_contacts_group_name,
                        pikerResultData = PickerResultData.ManageGroupContacts(
                            group = group,
                            selectedContacts = group.contacts, // initial selection only
                            allContacts = all
                        )
                    )
                }
            }
        )
    }

    fun startCreateGroup(accounts: List<Account>) {
        tracker.trackEvent("Picker_startCreateGroup")
        _state.update {
            PickerScreenState(
                isLoading = false,
                titleId = R.string.add_group,
                pikerResultData = PickerResultData.ManageGroups(
                    accountLists = accounts,
                    pickedAccount = null
                )
            )
        }
    }

    // ---- Confirms (Done button only) ----

    fun confirmRename(group: LabelItem, newNameRaw: String?) {
        val newName = newNameRaw?.trim().orEmpty()
        if (newName.isEmpty() || newName == group.groupName) {
            _events.trySend(PickerEvent.ShowErrorById(R.string.enter_group_name))
            return
        }
        _state.update { it.copy(isLoading = true) }
        launchOnIoResultInMain(
            work = { actions.renameGroup(group.id, newName) },
            onError = ::handleError,
            onSuccess = {
                tracker.trackEvent("Picker_rename_success")
                rebuildSnapshotFromProviderThenNotify()
            }
        )
    }

    fun confirmManageContacts(group: LabelItem, newSelected: List<Contact>) {
        // ✅ Update VM state with the user's latest selection BEFORE loading → avoids UI resetting
        _state.update { st ->
            val updatedData =
                (st.pikerResultData as? PickerResultData.ManageGroupContacts)?.let { mg ->
                    if (mg.group.id == group.id) mg.copy(selectedContacts = newSelected) else mg
                } ?: st.pikerResultData
            st.copy(isLoading = true, pikerResultData = updatedData)
        }

        launchOnIoResultInMain(
            work = { actions.updateGroupMembers(group.id, newSelected, group.contacts) },
            onError = ::handleError,
            onSuccess = {
                tracker.trackEvent("Picker_manageContacts_success")
                rebuildSnapshotFromProviderThenNotify()
            }
        )
    }

    fun confirmCreateGroup(nameRaw: String, account: Account?) {
        val name = nameRaw.trim()
        if (name.isEmpty()) {
            _events.trySend(PickerEvent.ShowErrorById(R.string.enter_group_name))
            return
        }
        _state.update { it.copy(isLoading = true) }
        launchOnIoResultInMain(
            work = { actions.createGroup(name, account) },
            onError = ::handleError,
            onSuccess = {
                tracker.trackEvent("Picker_createGroup_success")
                rebuildSnapshotFromProviderThenNotify()
            }
        )
    }

    fun resetGroupRingtones() {
        _state.update { it.copy(isLoading = true) }
        launchOnIoResultInMain(
            work = { actions.clearAllRingtones() },
            onError = ::handleError,
            onSuccess = {
                tracker.trackEvent("Picker_resetAllRingtones_success")
                rebuildSnapshotFromProviderThenNotify()
            }
        )
    }

    fun close() {
        viewModelScope.launch { _events.send(PickerEvent.Close) }
    }

    private fun handleError(error: Throwable) {
        tracker.trackError(error)
        _state.update { it.copy(isLoading = false) }
        _events.trySend(PickerEvent.ShowErrorText(error.localizedMessage))
    }
}
