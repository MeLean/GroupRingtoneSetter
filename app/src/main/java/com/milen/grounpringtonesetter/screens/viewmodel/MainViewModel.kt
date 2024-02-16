package com.milen.grounpringtonesetter.screens.viewmodel

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.GroupItem
import com.milen.grounpringtonesetter.screens.home.HomeScreenState
import com.milen.grounpringtonesetter.screens.home.HomeViewModelCallbacks
import com.milen.grounpringtonesetter.screens.picker.PickerScreenState
import com.milen.grounpringtonesetter.screens.picker.PickerViewModelCallbacks
import com.milen.grounpringtonesetter.screens.picker.data.PickerData
import com.milen.grounpringtonesetter.screens.picker.data.PickerResultData
import com.milen.grounpringtonesetter.utils.ContactsHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainViewModel(private val contactsHelper: ContactsHelper) : ViewModel() {
    private val _homeUiState = MutableStateFlow(HomeScreenState())
    private val _pickerUiState = MutableStateFlow(PickerScreenState())
    private var interstitialAd: InterstitialAd? = null


//    init {
//        viewModelScope.launch {
//            kotlin.runCatching {
//                when (pickerData) {
//                    is PickerData.GroupNameChange ->
//                        _pickerUiState.tryEmit(
//                            _pickerUiState.value.copy(
//                                isLoading = false,
//                            )
//                        )
//
//                    is PickerData.ContactsPiker -> {
//                        contactsHelper.getAllPhoneContacts()
//                            .run {
//                                _pickerUiState.tryEmit(
//                                    _pickerUiState.value.copy(
//                                        isLoading = false,
//                                        pikerData = pickerData.copy(allContacts = this)
//                                    )
//                                )
//                            }
//                    }
//                }
//            }
//        }
//    }

    fun getHomeViewModelCallbacks(): HomeViewModelCallbacks =
        HomeViewModelCallbacks(
            uiState = _homeUiState,
            onCreateGroup = ::onCreateGroup,
            onSetRingtones = ::onSetRingTones,
            onRingtoneChosen = ::onRingtoneChosen,
            onGroupDeleted = ::onGroupDeleted,
            fetchGroups = ::fetchGroups,
            hideLoading = ::hideLoading,
            setUpGroupNamePicking = ::setUpGroupNamePicking,
            setUpContactsPicking = ::setUpContactsPicking,
            loadAd = ::loadAd,
            showAd = ::showAd
        )

    fun getPickerViewModelCallbacks(): PickerViewModelCallbacks =
        PickerViewModelCallbacks(uiState = _pickerUiState)

    private fun fetchGroups() {
        updateGroupList(contactsHelper.getAllGroups())
    }

    private fun setUpGroupNamePicking(group: GroupItem) {
        _pickerUiState.tryEmit(
            PickerScreenState(
                isLoading = false,
                pikerData = PickerData.GroupNameChange(
                    titleId = R.string.edit_group_name,
                    groupItem = group
                )
            )
        )
    }

    private fun setUpContactsPicking(group: GroupItem) {
        val emit = _pickerUiState.tryEmit(PickerScreenState(isLoading = true))

        viewModelScope.launch {
            val contacts = contactsHelper.getAllPhoneContacts()
            _pickerUiState.tryEmit(
                PickerScreenState(
                    isLoading = false,
                    pikerData = getContactPickerData(group = group, allContacts = contacts)
                )
            )
        }
    }

    private fun getContactPickerData(group: GroupItem, allContacts: List<Contact> = emptyList()) =
        PickerData.ContactsPiker(
            titleId = R.string.manage_contacts_group_name,
            groupItem = group,
            allContacts = allContacts
        )

    private fun onCreateGroup(groupName: String?) {
        groupName?.let {
            contactsHelper.createGroup(it)
            updateGroupList(contactsHelper.getAllGroups())
        }
    }

    private fun loadAd(context: Context, adUnitId: String) {
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(context, adUnitId, adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitialAd = ad
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                interstitialAd = null
            }
        })
    }

    private fun showAd(activity: Activity) {
        interstitialAd?.show(activity)
    }

    private fun onSetRingTones(
        groupItems: List<GroupItem>,
        contentResolver: ContentResolver
    ) {
        showLoading()
        viewModelScope.launch {
            groupItems.map {
                it.ringtoneUri?.let { uri ->
                    contactsHelper.setRingtoneToGroup(
                        contentResolver = contentResolver,
                        groupId = it.id,
                        newRingtoneUri = uri
                    )
                }
            }

            _homeUiState.tryEmit(
                _homeUiState.value.copy(
                    isLoading = false,
                    isAllDone = true,
                    onDoneAd = interstitialAd
                )
            )
        }
    }

    private fun onRingtoneChosen(id: Long, uri: Uri?) {
        _homeUiState.value.groupItems.apply {
            firstOrNull { it.id == id }?.let {
                it.ringtoneUri = uri
            }

            _homeUiState.tryEmit(
                _homeUiState.value.copy(
                    id = System.currentTimeMillis(),
                    groupItems = this
                )
            )
        }
    }

    private fun updateGroupList(groups: List<GroupItem>) {
        _homeUiState.tryEmit(
            _homeUiState.value.copy(
                isLoading = false,
                groupItems = groups,
                areLabelsFetched = true
            )
        )
    }

    fun onPickerResult(result: PickerResultData) {
        when (result) {
            is PickerResultData.ContactsGroupChanged -> {
                contactsHelper.addAllContactsToGroup(result.groupId, result.includedContacts)
                contactsHelper.removeAllContactsFromGroup(result.groupId, result.excludedContacts)
                updateGroupList(contactsHelper.getAllGroups())
            }

            is PickerResultData.GroupNameChanged -> {
                result.newGroupName.takeIf { it.isNotEmpty() && it != result.oldName }
                    ?.let {
                        contactsHelper.updateGroupName(result.groupId, it)
                        updateGroupList(contactsHelper.getAllGroups())
                    }
            }

            is PickerResultData.Canceled -> Unit
        }
    }

    private fun onGroupDeleted(groupId: Long) {
        contactsHelper.deleteGroup(groupId = groupId)
        updateGroupList(contactsHelper.getAllGroups())
    }


    private fun hideLoading() {
        _homeUiState.tryEmit(
            _homeUiState.value.copy(
                isLoading = false
            )
        )
    }

    private fun showLoading() {
        _homeUiState.tryEmit(
            _homeUiState.value.copy(
                id = System.currentTimeMillis(),
                isLoading = true
            )
        )
    }
}