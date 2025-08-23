package com.milen.grounpringtonesetter.ui.picker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.repos.ContactsRepository
import com.milen.grounpringtonesetter.ui.picker.PickerEvent
import com.milen.grounpringtonesetter.ui.picker.PickerScreenState
import com.milen.grounpringtonesetter.ui.picker.data.PickerResultData
import com.milen.grounpringtonesetter.utils.Tracker
import com.milen.grounpringtonesetter.utils.launchOnIoResultInMain
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class PickerViewModel(
    private val tracker: Tracker,
    private val contactsRepo: ContactsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PickerScreenState())
    val state: StateFlow<PickerScreenState> =
        combine(
            _state,
            contactsRepo.allContacts
        ) { base, allContacts ->
            if (base.pikerResultData is PickerResultData.ManageGroupContacts) {
                if (allContacts == null) {
                    base.copy(isLoading = true)
                } else {
                    base.copy(
                        isLoading = false,
                        pikerResultData = base.pikerResultData.copy(
                            allContacts = allContacts
                        )
                    )
                }
            } else {
                base
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = _state.value
        )

    private val _events = Channel<PickerEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

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

    // Toggle from UI
    fun updateManageSelection(selectedContacts: List<Contact>) {
        val cur = _state.value.pikerResultData as? PickerResultData.ManageGroupContacts ?: return

        _state.update { st ->
            st.copy(
                pikerResultData = cur.copy(
                    selectedContacts = selectedContacts
                )
            )
        }
    }

    fun startManageContacts(group: LabelItem) {
        tracker.trackEvent("Picker_startManageContacts")
        _state.update {
            PickerScreenState(
                isLoading = false,
                titleId = R.string.manage_contacts_group_name,
                pikerResultData = PickerResultData.ManageGroupContacts(
                    group = group,
                    selectedContacts = group.contacts
                )
            )
        }
    }

    fun startCreateGroup() {
        tracker.trackEvent("Picker_startCreateGroup")
        _state.update {
            PickerScreenState(
                isLoading = false,
                titleId = R.string.add_group,
                pikerResultData = PickerResultData.ManageGroups()
            )
        }
    }

    fun confirmRename(group: LabelItem, newNameRaw: String?) {
        val newName = newNameRaw?.trim().orEmpty()
        if (newName.isEmpty() || newName == group.groupName) {
            _events.trySend(PickerEvent.ShowErrorById(R.string.enter_group_name))
            return
        }
        showLoading()
        launchOnIoResultInMain(
            work = { contactsRepo.renameGroup(group.id, newName) },
            onError = ::handleError,
            onSuccess = {
                tracker.trackEvent("Picker_rename_success")
                closeScreen()
            }
        )
    }

    fun confirmManageContacts(group: LabelItem) {
        val cur = _state.value.pikerResultData as? PickerResultData.ManageGroupContacts ?: return
        val newSelected = cur.selectedContacts
        showLoading()

        launchOnIoResultInMain(
            work = { contactsRepo.updateGroupMembers(group.id, newSelected, group.contacts) },
            onError = ::handleError,
            onSuccess = {
                tracker.trackEvent("Picker_manageContacts_success")
                closeScreen()
            }
        )
    }

    fun confirmCreateGroup(nameRaw: String) {
        val name = nameRaw.trim()
        if (name.isEmpty()) {
            _events.trySend(PickerEvent.ShowErrorById(R.string.enter_group_name))
            return
        }
        showLoading()
        launchOnIoResultInMain(
            work = { contactsRepo.createGroup(name) },
            onError = ::handleError,
            onSuccess = {
                tracker.trackEvent("Picker_createGroup_success")
                closeScreen()
            }
        )
    }

    fun resetGroupRingtones() {
        showLoading()
        launchOnIoResultInMain(
            work = { contactsRepo.clearAllRingtones() },
            onError = ::handleError,
            onSuccess = {
                tracker.trackEvent("Picker_resetAllRingtones_success")
                closeScreen()
            }
        )
    }

    fun close() {
        viewModelScope.launch { _events.send(PickerEvent.Close) }
    }

    private fun showLoading() {
        _state.update { it.copy(isLoading = true) }
    }

    private fun hideLoading() {
        _state.update { it.copy(isLoading = false) }
    }

    private fun handleError(error: Throwable) {
        tracker.trackError(error)
        hideLoading()
        _events.trySend(PickerEvent.ShowErrorText(error.localizedMessage))
    }

    private fun closeScreen() {
        // reset state
        _state.update { PickerScreenState(isLoading = false) }
        _events.trySend(PickerEvent.Close)
    }
}
