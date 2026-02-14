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
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import com.milen.grounpringtonesetter.utils.Tracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

internal class PickerViewModel(
    private val tracker: Tracker,
    private val contactsRepo: ContactsRepository,
) : ViewModel() {
    private data class PendingManageContactsSave(
        val group: LabelItem,
        val newSelected: List<Contact>,
        val oldSelected: List<Contact>,
    )

    private data class PendingBlockedContactsDecision(
        val group: LabelItem,
        val newSelected: List<Contact>,
        val oldSelected: List<Contact>,
        val blockedContactIds: Set<Long>,
    )

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
    private var pendingManageContactsSave: PendingManageContactsSave? = null
    private var pendingBlockedContactsDecision: PendingBlockedContactsDecision? = null

    fun startRename(group: LabelItem) {
        tracker.trackEvent("Picker_startRename")
        clearPendingManageContactDecisions()
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

    private var enrichJob: Job? = null
    private fun startUpdateContactsForLabel(labelId: Long) {
        enrichJob?.cancel()
        enrichJob = viewModelScope.launch {
            try {
                contactsRepo.enrichGroupContactsBasics(labelId)
            } catch (_: CancellationException) {
                // ignored
            } catch (t: Throwable) {
                tracker.trackError(t)
            }
        }
    }

    fun startManageContacts(group: LabelItem) {
        tracker.trackEvent("Picker_startManageContacts")
        clearPendingManageContactDecisions()
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

        if (contactsRepo.allContacts.value == null) {
            viewModelScope.launch {
                showLoading()
                try {
                    contactsRepo.refreshAllPhoneContacts()
                } catch (_: CancellationException) {
                } catch (t: Throwable) {
                    tracker.trackError(t)
                } finally {
                    hideLoading()
                }
            }
        }

        startUpdateContactsForLabel(group.id)
    }

    fun startCreateGroup() {
        tracker.trackEvent("Picker_startCreateGroup")
        clearPendingManageContactDecisions()
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
        viewModelScope.launch {
            val result = runCatching {
                withContext(DispatchersProvider.io) {
                    contactsRepo.renameGroup(group.id, newName)
                }
            }
            result.onSuccess {
                tracker.trackEvent("Picker_rename_success")
                closeScreen()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                handleError(e)
            }
        }
    }

    fun confirmManageContacts(group: LabelItem) {
        clearPendingManageContactDecisions()
        val cur = _state.value.pikerResultData as? PickerResultData.ManageGroupContacts ?: return
        val newSelected = cur.selectedContacts.distinctBy { it.id }
        val oldSelected = group.contacts.distinctBy { it.id }
        val toAdd = calculateContactsToAdd(
            newSelected = newSelected,
            oldSelected = oldSelected
        )

        if (toAdd.isEmpty()) {
            continueManageContactsWithRingtoneDecision(
                group = group,
                newSelected = newSelected,
                oldSelected = oldSelected
            )
            return
        }

        viewModelScope.launch {
            showLoading()
            val validationResult = runCatching {
                withContext(DispatchersProvider.io) {
                    contactsRepo.validateGroupReassignment(
                        groupId = group.id,
                        candidates = toAdd
                    )
                }
            }
            hideLoading()

            validationResult.onSuccess { validation ->
                if (validation.blocked.isEmpty()) {
                    continueManageContactsWithRingtoneDecision(
                        group = group,
                        newSelected = newSelected,
                        oldSelected = oldSelected
                    )
                    return@onSuccess
                }

                val blockedContactIds = validation.blocked.mapTo(hashSetOf()) { it.id }
                pendingBlockedContactsDecision = PendingBlockedContactsDecision(
                    group = group,
                    newSelected = newSelected,
                    oldSelected = oldSelected,
                    blockedContactIds = blockedContactIds
                )
                val blockedNames = validation.blocked
                    .map { blocked ->
                        blocked.name.ifBlank {
                            blocked.phone?.takeIf { it.isNotBlank() } ?: blocked.id.toString()
                        }
                    }
                    .distinct()
                _events.trySend(
                    PickerEvent.AskBlockedContactsContinueOrAbort(blockedNames)
                )
            }.onFailure { e ->
                if (e is CancellationException) throw e
                handleError(e)
            }
        }
    }

    fun onRequiredRingtoneChosen(uri: String) {
        val pending = pendingManageContactsSave ?: return
        pendingManageContactsSave = null
        runManageContactsSave(
            group = pending.group,
            newSelected = pending.newSelected,
            oldSelected = pending.oldSelected,
            ringtoneForNewContactsUri = uri
        )
    }

    fun onBlockedContactsContinue() {
        val pending = pendingBlockedContactsDecision ?: return
        pendingBlockedContactsDecision = null
        val filteredNewSelection = pending.newSelected
            .filterNot { it.id in pending.blockedContactIds }
            .distinctBy { it.id }

        continueManageContactsWithRingtoneDecision(
            group = pending.group,
            newSelected = filteredNewSelection,
            oldSelected = pending.oldSelected.distinctBy { it.id }
        )
    }

    fun onBlockedContactsAbort() {
        pendingBlockedContactsDecision = null
    }

    fun confirmCreateGroup(nameRaw: String) {
        val name = nameRaw.trim()
        if (name.isEmpty()) {
            _events.trySend(PickerEvent.ShowErrorById(R.string.enter_group_name))
            return
        }
        showLoading()

        viewModelScope.launch {
            val result = runCatching {
                withContext(DispatchersProvider.io) {
                    contactsRepo.createGroup(name)
                }
            }
            result.onSuccess {
                tracker.trackEvent("Picker_createGroup_success")
                closeScreen()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                handleError(e)
            }
        }
    }

    fun resetGroupRingtones() {
        showLoading()
        viewModelScope.launch {
            showLoading()
            try {
                contactsRepo.clearAllRingtones() // suspend; IO inside helper
                closeScreen()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                handleError(t)
            } finally {
                hideLoading()
            }
        }
    }

    fun close() {
        clearPendingManageContactDecisions()
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
        clearPendingManageContactDecisions()
        // reset state
        _state.update { PickerScreenState(isLoading = false) }
        _events.trySend(PickerEvent.Close)
    }

    private fun continueManageContactsWithRingtoneDecision(
        group: LabelItem,
        newSelected: List<Contact>,
        oldSelected: List<Contact>,
    ) {
        val normalizedNewSelected = newSelected.distinctBy { it.id }
        val normalizedOldSelected = oldSelected.distinctBy { it.id }
        val toAdd = calculateContactsToAdd(
            newSelected = normalizedNewSelected,
            oldSelected = normalizedOldSelected
        )

        if (toAdd.isEmpty()) {
            runManageContactsSave(
                group = group,
                newSelected = normalizedNewSelected,
                oldSelected = normalizedOldSelected,
                ringtoneForNewContactsUri = null
            )
            return
        }

        val groupRingtoneUris = group.ringtoneUriList
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        when (groupRingtoneUris.size) {
            0 -> runManageContactsSave(
                group = group,
                newSelected = normalizedNewSelected,
                oldSelected = normalizedOldSelected,
                ringtoneForNewContactsUri = null
            )

            1 -> runManageContactsSave(
                group = group,
                newSelected = normalizedNewSelected,
                oldSelected = normalizedOldSelected,
                ringtoneForNewContactsUri = groupRingtoneUris.first()
            )

            else -> {
                val options = contactsRepo.getRingtoneChoiceOptions(groupRingtoneUris)
                if (options.isEmpty()) {
                    runManageContactsSave(
                        group = group,
                        newSelected = normalizedNewSelected,
                        oldSelected = normalizedOldSelected,
                        ringtoneForNewContactsUri = null
                    )
                    return
                }

                pendingManageContactsSave = PendingManageContactsSave(
                    group = group,
                    newSelected = normalizedNewSelected,
                    oldSelected = normalizedOldSelected
                )
                _events.trySend(PickerEvent.AskNewContactsRingtoneChoice(options))
            }
        }
    }

    private fun clearPendingManageContactDecisions() {
        pendingManageContactsSave = null
        pendingBlockedContactsDecision = null
    }

    private fun runManageContactsSave(
        group: LabelItem,
        newSelected: List<Contact>,
        oldSelected: List<Contact>,
        ringtoneForNewContactsUri: String?,
    ) {
        showLoading()

        viewModelScope.launch {
            val result = runCatching {
                withContext(DispatchersProvider.io) {
                    contactsRepo.updateGroupMembers(
                        groupId = group.id,
                        newSelected = newSelected,
                        oldSelected = oldSelected,
                        ringtoneForNewContactsUri = ringtoneForNewContactsUri
                    )
                }
            }
            result.onSuccess {
                tracker.trackEvent("Picker_manageContacts_success")
                closeScreen()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                handleError(e)
            }
        }
    }

    private fun calculateContactsToAdd(
        newSelected: List<Contact>,
        oldSelected: List<Contact>,
    ): List<Contact> {
        val oldIds = oldSelected.mapTo(hashSetOf()) { it.id }
        return newSelected.filter { it.id !in oldIds }
    }
}
