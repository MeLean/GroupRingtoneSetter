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
import com.milen.grounpringtonesetter.utils.launchOnIoResultInMain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainViewModel(
    private val adHelper: AdLoadingHelper,
    private val dialogShower: DialogShower,
    private val contactsHelper: ContactsHelper,
    private val encryptedPrefs: EncryptedPreferencesHelper,
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
        _homeUiState.value = _homeUiState.value.copy(
            isLoading = true,
            arePermissionsGranted = true
        )
        updateGroupList()
    }

    fun setUpGroupNameEditing(group: GroupItem) {
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

    fun setUpContactsManaging(group: GroupItem) {
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
        }

    fun onPickerResult(result: PickerResultData) {
        showHomeLoading()
        setPickerLoadingForResult(result)
        when (result) {
            is PickerResultData.ManageGroupContacts ->
                launchOnIoResultInMain(
                    work = { manageContacts(result) },
                    onError = ::handleError,
                    onSuccess = { updateGroupList() }
                )

            is PickerResultData.GroupNameChange ->
                launchOnIoResultInMain(
                    work = { manageGroupChange(result) },
                    onError = ::handleError,
                    onSuccess = { updateGroupList() }
                )

            is PickerResultData.GroupSetName ->
                launchOnIoResultInMain(
                    work = { manageGroupSet(result) },
                    onError = ::handleError,
                    onSuccess = { updateGroupList(scrollTo = groups.size - 1) }
                )

            is PickerResultData.Canceled -> Unit
        }
        hideHomeLoading()
    }

    fun onRingtoneChosen(uri: Uri, fileName: String) {
        showHomeLoading()
        launchOnIoResultInMain(
            work = {
                val uriStr = "$uri"
                encryptedPrefs.putString(uri.toString(), fileName)
                _groups = _groups?.map { group ->
                    if (group.id == selectingGroup.id) {
                        group.copy(
                            ringtoneUriStr = uriStr,
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
        showHomeLoading()
        launchOnIoResultInMain(
            work = {
                var noRingtoneSelected = true
                groups.forEach {
                    it.ringtoneUriStr?.let { uriStr ->
                        noRingtoneSelected = false
                        contactsHelper.setRingtoneToGroupContacts(
                            groupContacts = it.contacts,
                            newRingtoneUriStr = uriStr
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
        _pickerUiState.tryEmit(
            PickerScreenState(
                titleId = R.string.add_group,
                isLoading = false,
                pikerResultData = PickerResultData.GroupSetName()
            )
        )
    }

    private fun handleError(error: Throwable) {
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
            ?: throw IllegalArgumentException()
    }

    private fun manageGroupChange(result: PickerResultData.GroupNameChange): Unit =
        result.newGroupName.takeIf { it?.isNotEmpty() == true && it != result.groupItem.groupName }
            ?.let { name ->
                contactsHelper.updateGroupName(result.groupItem.id, name)
                val updatedGroups = contactsHelper.getAllGroups()
                _groups = updatedGroups.toMutableList()
            }
            ?: throw IllegalArgumentException()

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
}