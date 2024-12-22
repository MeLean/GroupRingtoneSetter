package com.milen.grounpringtonesetter.screens.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.customviews.dialog.DialogShower
import com.milen.grounpringtonesetter.customviews.ui.ads.AdLoadingHelper
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.GroupItem
import com.milen.grounpringtonesetter.screens.home.HomeScreenState
import com.milen.grounpringtonesetter.screens.picker.PickerScreenState
import com.milen.grounpringtonesetter.screens.picker.data.PickerResultData
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.utils.Tracker
import com.milen.grounpringtonesetter.utils.launchOnIoResultInMain
import com.milen.grounpringtonesetter.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainViewModel(
    private val adHelper: AdLoadingHelper,
    private val dialogShower: DialogShower,
    private val contactsHelper: ContactsHelper,
    private val encryptedPrefs: EncryptedPreferencesHelper,
    private val tracker: Tracker,
) : ViewModel() {
    private var _groups: MutableList<GroupItem>? = null
    private val groups: List<GroupItem>
        get() = _groups ?: contactsHelper.getAllGroups().also {
            _groups = it.toMutableList()
        }

    private val _homeUiState = MutableStateFlow(HomeScreenState())
    val homeUiState: StateFlow<HomeScreenState>
        get() = _homeUiState.asStateFlow()

    private val _pickerUiState =
        MutableStateFlow(PickerScreenState(titleId = R.string.loading))
    val pickerUiState: StateFlow<PickerScreenState>
        get() = _pickerUiState.asStateFlow()

    private var _selectingGroup: GroupItem? = null
    var selectingGroup: GroupItem
        get() = _selectingGroup
            ?: throw UninitializedPropertyAccessException("_selectingGroup not initialized")
        set(value) {
            _selectingGroup = value
        }

    fun showInfoDialog(): Unit = dialogShower.showInfo()

    fun onNoPermissions() {
        _homeUiState.tryEmit(
            _homeUiState.value.copy(
                isLoading = false,
                arePermissionsGranted = false
            )
        )
    }

    fun onPermissionsGranted() {
        tracker.trackEvent("onPermissionsGranted")
        _homeUiState.value = _homeUiState.value.copy(
            isLoading = true,
            arePermissionsGranted = true
        )
        updateGroupList()
    }

    fun setUpGroupNameEditing(group: GroupItem) {
        tracker.trackEvent("setUpGroupNameEditing")
        _pickerUiState.tryEmit(
            PickerScreenState(
                isLoading = false,
                titleId = R.string.edit_group_name,
                pikerResultData = PickerResultData.GroupNameChange(
                    groupItem = group
                )
            )
        )
    }

    fun onPermissionsRefused(): Unit =
        dialogShower.showErrorById(R.string.need_permission_to_run)
            .also { tracker.trackEvent("onPermissionsRefused") }

    fun setUpContactsManaging(group: GroupItem) {
        tracker.trackEvent("setUpContactsManaging")
        setPickerLoadingForResult().also {
            launchOnIoResultInMain(
                work = { contactsHelper.getAllPhoneContacts() },
                onError = ::handleError,
                onSuccess = {
                    _pickerUiState.tryEmit(
                        PickerScreenState(
                            isLoading = false,
                            titleId = R.string.manage_contacts_group_name,
                            pikerResultData = getContactPickerData(group = group, allContacts = it)
                        )
                    )
                }
            )
        }
    }

    fun onGroupDeleted(groupItem: GroupItem): Unit =
        showHomeLoading().also {
            launchOnIoResultInMain(
                work = {
                    contactsHelper.deleteGroup(groupId = groupItem.id)
                    _groups?.remove(groupItem)
                },
                onError = ::handleError,
                onSuccess = { updateGroupList() }
            )
        }.also { tracker.trackEvent("onGroupDeleted") }

    fun onPickerResult(result: PickerResultData) {
        tracker.trackEvent("onPickerResult")
        showHomeLoading()
        setPickerLoadingForResult(result)
        when (result) {
            is PickerResultData.ManageGroupContacts ->
                launchOnIoResultInMain(
                    work = { manageContacts(result) },
                    onError = ::handleError,
                    onSuccess = { updateGroupList() }
                ).also { tracker.trackEvent("ManageGroupContacts") }

            is PickerResultData.GroupNameChange ->
                launchOnIoResultInMain(
                    work = { manageGroupChange(result) },
                    onError = ::handleError,
                    onSuccess = { updateGroupList() }
                ).also { tracker.trackEvent("GroupNameChange") }

            is PickerResultData.GroupSetName ->
                launchOnIoResultInMain(
                    work = { manageGroupSet(result) },
                    onError = ::handleError,
                    onSuccess = { updateGroupList(scrollTo = groups.size - 1) }
                ).also { tracker.trackEvent("GroupSetName") }

            is PickerResultData.Canceled -> Unit
        }
        hideHomeLoading()
    }

    fun onRingtoneChosen(uri: Uri, fileName: String) {
        tracker.trackEvent("onRingtoneChosen")
        showHomeLoading()
        launchOnIoResultInMain(
            work = {
                val uriStr = "$uri"
                encryptedPrefs.putString(uri.toString(), fileName).also {
                    "onRingtoneChosen saved uri: $uriStr fileName: $fileName".log()
                }
                _groups = _groups?.map { group ->
                    if (group.id == selectingGroup.id) {
                        group.copy(
                            ringtoneUriStr = listOf(uriStr),
                            ringtoneFileName = fileName,
                            contacts = group.contacts.map { contact ->
                                contact.copy(ringtoneUriStr = uriStr)
                            }
                        )
                    } else {
                        group
                    }
                }.also {
                    _selectingGroup = null
                }?.toMutableList()
            },
            onError = ::handleError,
            onSuccess = { updateGroupList() }
        )
    }

    fun onSetRingtones() {
        tracker.trackEvent("onSetRingtones")
        showHomeLoading()
        launchOnIoResultInMain(
            work = {
                var noRingtoneSelected = true
                groups.forEach {
                    "onSetRingtones: ${it.groupName} count:${it.contacts.count()} uri:${it.ringtoneUriStr}".log()
                    it.ringtoneUriStr
                        .takeIf { list -> list.isNotEmpty() }
                        ?.let { uriStr ->
                        noRingtoneSelected = false
                        contactsHelper.setRingtoneToGroupContacts(
                            groupContacts = it.contacts,
                            newRingtoneUriStr = uriStr.first()
                        )
                    }
                }

                noRingtoneSelected
            },
            onError = ::handleError,
            onSuccess = { noRingtoneSelected ->
                if (noRingtoneSelected) {
                    hideHomeLoading()
                    dialogShower.showErrorById(R.string.no_ringtone_selected)
                } else {
                    adHelper.run {
                        loadInterstitialAd {
                            hideHomeLoading()
                            showInterstitialAd()
                        }
                    }
                }
            }
        )
    }

    fun setUpGroupCreateRequest() {
        tracker.trackEvent("setUpGroupCreateRequest")
        _pickerUiState.tryEmit(
            PickerScreenState(
                titleId = R.string.add_group,
                isLoading = false,
                pikerResultData = PickerResultData.GroupSetName()
            )
        )
    }

    private fun handleError(error: Throwable) {
        tracker.trackError(error)
        hideHomeLoading()
        hidePickerLoading()
        dialogShower.showError(error.localizedMessage)
    }

    private fun manageGroupSet(result: PickerResultData.GroupSetName) {
        result.groupName.takeIf { it.isNotEmpty() }
            ?.let { str ->
                contactsHelper.createGroup(str)?.let { newGroup ->
                    _groups?.add(newGroup)
                    _groups = contactsHelper.getAllGroups().toMutableList()
                }
            }
            ?: throw IllegalArgumentException("Group name is empty")
    }

    private fun manageGroupChange(result: PickerResultData.GroupNameChange): Unit =
        result.newGroupName.takeIf { it?.isNotEmpty() == true && it != result.groupItem.groupName }
            ?.let { name ->
                contactsHelper.updateGroupName(result.groupItem.id, name)
                val updatedGroups = contactsHelper.getAllGroups()
                _groups = updatedGroups.toMutableList()
            }
            ?: throw IllegalArgumentException("${result.newGroupName} could not be applied on ${result.groupItem}")

    private fun manageContacts(result: PickerResultData.ManageGroupContacts) {
        contactsHelper.addAllContactsToGroup(result.group.id, result.selectedContacts)
        val excludedContacts = result.group.contacts.filterNot { oldContact ->
            result.selectedContacts.any { newContact -> newContact.id == oldContact.id }
        }
        contactsHelper.removeAllContactsFromGroup(result.group.id, excludedContacts)

        val updatedGroups = contactsHelper.getAllGroups()

        _groups = updatedGroups.toMutableList()
    }

    private fun updateGroupList(scrollTo: Int? = null) {
        launchOnIoResultInMain(
            work = { groups },
            onSuccess = { list ->
                "updateGroupList ${list.joinToString()}".log()
                _homeUiState.update {
                    _homeUiState.value.copy(
                        isLoading = false,
                        groupItems = list,
                        scrollToPosition = scrollTo
                    )
                }
            },
            onError = ::handleError
        )
    }

    private fun setPickerLoadingForResult(result: PickerResultData? = null) {
        _pickerUiState.update {
            _pickerUiState.value.copy(
                isLoading = true,
                pikerResultData = result
            )
        }
    }

    private fun showHomeLoading(): Unit =
        _homeUiState.update { _homeUiState.value.copy(isLoading = true) }


    private fun hideHomeLoading(): Unit =
        _homeUiState.update { _homeUiState.value.copy(isLoading = false) }

    private fun hidePickerLoading(): Unit =
        _pickerUiState.update { _pickerUiState.value.copy(isLoading = false) }

    private fun getContactPickerData(group: GroupItem, allContacts: List<Contact> = emptyList()) =
        PickerResultData.ManageGroupContacts(
            group = group,
            selectedContacts = group.contacts,
            allContacts = allContacts
        )

    fun trackNoneFatal(error: IllegalArgumentException): Unit =
        tracker.trackError(error)

}