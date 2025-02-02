package com.milen.grounpringtonesetter.screens.viewmodel

import android.accounts.Account
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.customviews.dialog.DialogShower
import com.milen.grounpringtonesetter.customviews.ui.ads.AdLoadingHelper
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
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
import kotlinx.coroutines.launch

class MainViewModel(
    private val adHelper: AdLoadingHelper,
    private val dialogShower: DialogShower,
    private val contactsHelper: ContactsHelper,
    private val encryptedPrefs: EncryptedPreferencesHelper,
    private val tracker: Tracker,
) : ViewModel() {
    private var _groups: MutableList<LabelItem>? = null
    private val groups: List<LabelItem>
        get() = _groups ?: contactsHelper.getAllLabelItems().also {
            _groups = it.toMutableList()
        }

    private val _homeUiState = MutableStateFlow(HomeScreenState())
    val homeUiState: StateFlow<HomeScreenState>
        get() = _homeUiState.asStateFlow()

    private val _pickerUiState =
        MutableStateFlow(PickerScreenState(titleId = R.string.loading))
    val pickerUiState: StateFlow<PickerScreenState>
        get() = _pickerUiState.asStateFlow()

    private var _selectingGroup: LabelItem? = null
    var selectingGroup: LabelItem
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

    fun setUpGroupNameEditing(group: LabelItem) {
        tracker.trackEvent("setUpGroupNameEditing")
        _pickerUiState.tryEmit(
            PickerScreenState(
                isLoading = false,
                titleId = R.string.edit_group_name,
                pikerResultData = PickerResultData.GroupNameChange(
                    labelItem = group
                )
            )
        )
    }

    fun onPermissionsRefused(): Unit =
        dialogShower.showErrorById(R.string.need_permission_to_run)
            .also { tracker.trackEvent("onPermissionsRefused") }

    fun setUpContactsManaging(group: LabelItem) {
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

    fun onGroupDeleted(labelItem: LabelItem): Unit =
        showHomeLoading().also {
            launchOnIoResultInMain(
                work = { contactsHelper.deleteLabel(labelId = labelItem.id) },
                onError = ::handleError,
                onSuccess = {
                    val newGroups = _groups?.filter { it.id != labelItem.id }.orEmpty()
                    _groups = newGroups.toMutableList()
                    _homeUiState.value = homeUiState.value.copy(
                        isLoading = false,
                        labelItems = newGroups,
                    )
                }
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

            is PickerResultData.ManageGroups ->
                launchOnIoResultInMain(
                    work = { createGroupByName(result.groupName, result.pickedAccount) },
                    onError = ::handleError,
                    onSuccess = { groupItem ->
                        val newGroups = _groups?.toMutableList()
                            ?.also { it.add(groupItem) }
                            .orEmpty()

                        _homeUiState.value = homeUiState.value.copy(
                            isLoading = false,
                            labelItems = newGroups,
                            scrollToBottom = true
                        )
                    }
                ).also { tracker.trackEvent("ManageGroups") }

            is PickerResultData.Canceled -> hideHomeLoading()
        }
    }

    fun onRingtoneChosen(uri: Uri, fileName: String) {
        tracker.trackEvent("onRingtoneChosen")
        showHomeLoading()
        launchOnIoResultInMain(
            work = {
                val uriStr = "$uri"
                encryptedPrefs.saveString(uri.toString(), fileName).also {
                    "onRingtoneChosen saved uri: $uriStr fileName: $fileName".log()
                }
                _groups = _groups?.map { group ->
                    if (group.id == selectingGroup.id) {
                        group.copy(
                            ringtoneUriList = listOf(uriStr),
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

    fun onSetAllGroupsRingtones() {
        tracker.trackEvent("onSetAllGroupsRingtones")
        showHomeLoading()
        launchOnIoResultInMain(
            work = {
                var noRingtoneSelected = true
                groups.forEach {
                    "onSetRingtones: ${it.groupName} count:${it.contacts.count()} uri:${it.ringtoneUriList}".log()
                    it.ringtoneUriList
                        .takeIf { list -> list.isNotEmpty() }
                        ?.let { uriStr ->
                            noRingtoneSelected = false
                            setRingtoneToGroupContacts(it, uriStr)
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
                    showInterstitialAd()
                }
            }
        )
    }

    private fun setRingtoneToGroupContacts(
        labelItem: LabelItem,
        uriStr: List<String>,
    ) {
        viewModelScope.launch {
            contactsHelper.setRingtoneToLabelContacts(
                labelContacts = labelItem.contacts,
                newRingtoneUriStr = uriStr.first()
            )
        }
    }

    fun setUpGroupCreateRequest() {
        tracker.trackEvent("setUpGroupCreateRequest")
        val accounts = contactsHelper.getGoogleAccounts()
        _pickerUiState.tryEmit(
            PickerScreenState(
                titleId = R.string.add_group,
                isLoading = false,
                pikerResultData = PickerResultData.ManageGroups(
                    accountLists = accounts,
                    pickedAccount = accounts.takeIf { it.size == 1 }?.let { accounts.first() }
                )
            )
        )
    }

    fun onApplySingleRingtone(labelItem: LabelItem) {
        with(labelItem) {

            if (contacts.isEmpty()) {
                tracker.trackEvent("onSingleRingtoneSetNoContacts")
                dialogShower.showErrorById(R.string.no_contacts)
                return
            }

            if (ringtoneUriList.isEmpty()) {
                tracker.trackEvent("onSingleRingtoneSetNoRingtones")
                dialogShower.showErrorById(R.string.no_ringtone_selected)
                return
            }

            tracker.trackEvent("onSingleRingtoneSet")
            showHomeLoading()
            launchOnIoResultInMain(
                work = { setRingtoneToGroupContacts(this, ringtoneUriList) },
                onError = ::handleError,
                onSuccess = {
                    showInterstitialAd()
                }
            )
        }
    }

    fun trackNoneFatal(error: Exception): Unit =
        tracker.trackError(error)

    private fun handleError(error: Throwable) {
        tracker.trackError(error)
        hideHomeLoading()
        hidePickerLoading()
        dialogShower.showError(error.localizedMessage)
    }

    private fun showInterstitialAd() {
        adHelper.run {
            loadInterstitialAd {
                hideHomeLoading()
                showInterstitialAd()
            }
        }
    }

    private fun createGroupByName(name: String, account: Account?): LabelItem =
        name.takeIf { it.isNotEmpty() }
            ?.let { noneEmptyName -> contactsHelper.createLabel(noneEmptyName, account) }
            ?: throw IllegalArgumentException("Group name is empty")

    private fun manageGroupChange(result: PickerResultData.GroupNameChange): Unit =
        result.newGroupName.takeIf { it?.isNotEmpty() == true && it != result.labelItem.groupName }
            ?.let { name ->
                contactsHelper.updateLabelName(result.labelItem.id, name)
                val updatedGroups = contactsHelper.getAllLabelItems()
                _groups = updatedGroups.toMutableList()
            }
            ?: throw IllegalArgumentException("${result.newGroupName} could not be applied on ${result.labelItem}")

    private fun manageContacts(result: PickerResultData.ManageGroupContacts) {
        contactsHelper.addAllContactsToLabel(result.group.id, result.selectedContacts)
        val excludedContacts = result.group.contacts.filterNot { oldContact ->
            result.selectedContacts.any { newContact -> newContact.id == oldContact.id }
        }
        contactsHelper.removeAllContactsFromLabel(result.group.id, excludedContacts)

        val updatedGroups = contactsHelper.getAllLabelItems()

        _groups = updatedGroups.toMutableList()
    }

    private fun updateGroupList() {
        launchOnIoResultInMain(
            work = { groups },
            onSuccess = { list ->
                _homeUiState.update {
                    _homeUiState.value.copy(
                        isLoading = false,
                        labelItems = list
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

    private fun getContactPickerData(group: LabelItem, allContacts: List<Contact> = emptyList()) =
        PickerResultData.ManageGroupContacts(
            group = group,
            selectedContacts = group.contacts,
            allContacts = allContacts
        )
}