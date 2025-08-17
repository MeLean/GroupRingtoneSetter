package com.milen.grounpringtonesetter.ui.viewmodel

import android.accounts.Account
import android.app.Activity
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.BillingClient
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.BuildConfig
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.actions.GroupActions
import com.milen.grounpringtonesetter.billing.BillingEntitlementManager
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.dialog.DialogShower
import com.milen.grounpringtonesetter.customviews.ui.ads.AdLoadingHelper
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.accounts.AccountsResolver
import com.milen.grounpringtonesetter.data.cache.ContactsSnapshotStore
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.data.prefs.SelectedAccountsStore
import com.milen.grounpringtonesetter.data.repos.ContactsRepository
import com.milen.grounpringtonesetter.ui.home.HomeEvent
import com.milen.grounpringtonesetter.ui.home.HomeScreenState
import com.milen.grounpringtonesetter.ui.picker.PickerScreenState
import com.milen.grounpringtonesetter.ui.picker.data.PickerResultData
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.Tracker
import com.milen.grounpringtonesetter.utils.launch
import com.milen.grounpringtonesetter.utils.launchOnIoResultInMain
import com.milen.grounpringtonesetter.utils.log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal class MainViewModel(
    private val appContext: App,
    private val adHelper: AdLoadingHelper,
    private val dialogShower: DialogShower,
    private val contactsHelper: ContactsHelper,
    private val encryptedPrefs: EncryptedPreferencesHelper,
    private val tracker: Tracker,
    private val billing: BillingEntitlementManager,
    private val contactsRepo: ContactsRepository,
    private val groupActions: GroupActions,
) : ViewModel() {

    private fun accountsKey(): String = SelectedAccountsStore.accountsKeyOrAll(encryptedPrefs)

    // SWR guard
    private val isRefreshing = AtomicBoolean(false)
    private var lastRefreshAt = 0L
    private val refreshCooldownMs = 800L

    // Events & state
    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    private val _state = MutableStateFlow(HomeScreenState())

    val state: StateFlow<HomeScreenState> =
        combine(
            _state,
            billing.state,
        ) { base, entitlement ->
            val combinedLoading =
                base.isLoading || entitlement == EntitlementState.UNKNOWN

            base.copy(
                entitlement = entitlement,
                isLoading = combinedLoading
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = _state.value
        )

    private val _pickerUiState = MutableStateFlow(PickerScreenState(titleId = R.string.loading))
    val pickerUiState: StateFlow<PickerScreenState> = _pickerUiState.asStateFlow()

    private var _selectingGroup: LabelItem? = null
    var selectingGroup: LabelItem
        get() = _selectingGroup
            ?: throw UninitializedPropertyAccessException("_selectingGroup not initialized")
        set(value) {
            _selectingGroup = value
        }

    fun showInfoDialog(): Unit =
        dialogShower.showInfo(additionalText = BuildConfig.VERSION_NAME)

    fun onNoPermissions() {
        _state.tryEmit(
            _state.value.copy(
                isLoading = false,
                arePermissionsGranted = false
            )
        )
    }

    fun onConnectionChanged(isOnline: Boolean) {
        if (!isOnline && state.value.entitlement != EntitlementState.OWNED) {
            tracker.trackEvent("onConnectionChanged")
            launch { _events.send(HomeEvent.ConnectionLost) }
        }
    }

    fun onPermissionsGranted() {
        tracker.trackEvent("onPermissionsGranted")
        _state.value = _state.value.copy(
            isLoading = true,
            arePermissionsGranted = true
        )
        ensureAccountSelectionOrAskOnce()
        if (SelectedAccountsStore.hasSelection(encryptedPrefs)) {
            updateGroupList()
        }
    }

    fun onAccountsSelected(selected: Set<String>?) {
        if (selected.isNullOrEmpty()) {
            ensureAccountSelectionOrAskOnce()
            return
        }
        SelectedAccountsStore.write(encryptedPrefs, selected)
        invalidateAndUpdate()
    }

    fun setUpGroupNameEditing(group: LabelItem) {
        tracker.trackEvent("setUpGroupNameEditing")
        _pickerUiState.tryEmit(
            PickerScreenState(
                isLoading = false,
                titleId = R.string.edit_group_name,
                pikerResultData = PickerResultData.GroupNameChange(labelItem = group)
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

    fun onGroupDeleted(labelItem: LabelItem) {
        showHomeLoading()
        launchOnIoResultInMain(
            work = { groupActions.deleteGroup(labelItem.id); true },
            onError = ::handleError,
            onSuccess = {
                invalidateAndUpdate()
                tracker.trackEvent("onGroupDeleted")
            }
        )
    }

    fun onPickerResult(result: PickerResultData) {
        tracker.trackEvent("onPickerResult")
        showHomeLoading()
        setPickerLoadingForResult(result)
        when (result) {
            is PickerResultData.ManageGroupContacts ->
                launchOnIoResultInMain(
                    work = { manageContacts(result) },
                    onError = ::handleError,
                    onSuccess = { invalidateAndUpdate() }
                ).also { tracker.trackEvent("ManageGroupContacts") }

            is PickerResultData.GroupNameChange ->
                launchOnIoResultInMain(
                    work = { manageGroupChange(result) },
                    onError = ::handleError,
                    onSuccess = { invalidateAndUpdate() }
                ).also { tracker.trackEvent("GroupNameChange") }

            is PickerResultData.ManageGroups ->
                launchOnIoResultInMain(
                    work = { createGroupByName(result.groupName, result.pickedAccount) },
                    onError = ::handleError,
                    onSuccess = { _ ->
                        // Hint UI to scroll on next render
                        _state.update { it.copy(isLoading = false, scrollToBottom = true) }
                        // Reconcile from source of truth
                        invalidateAndUpdate()
                    }
                ).also { tracker.trackEvent("ManageGroups") }

            is PickerResultData.Canceled -> hideHomeLoading()
        }
    }

    fun onRingtoneChosen(uri: Uri, fileName: String) {
        tracker.trackEvent("onRingtoneChosen")
        showHomeLoading()
        val group = _selectingGroup ?: run {
            hideHomeLoading()
            return
        }

        launchOnIoResultInMain(
            work = {
                val uriStr = uri.toString()
                encryptedPrefs.saveString(uriStr, fileName)
                "onRingtoneChosen saved uri: $uriStr fileName: $fileName".log()
                groupActions.setGroupRingtone(group.contacts, uriStr)
                true
            },
            onError = ::handleError,
            onSuccess = {
                _selectingGroup = null
                invalidateAndUpdate()
                showInterstitialAdIfNeeded()
            }
        )
    }

    fun onSetAllGroupsRingtones() {
        tracker.trackEvent("onSetAllGroupsRingtones")
        showHomeLoading()
        launchOnIoResultInMain(
            work = {
                val current = state.value.labelItems
                var noRingtoneSelected = true
                current.forEach { item ->
                    item.ringtoneUriList.firstOrNull()?.let { uriStr ->
                        noRingtoneSelected = false
                        groupActions.setGroupRingtone(item.contacts, uriStr)
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
                    invalidateAndUpdate()
                    showInterstitialAdIfNeeded()
                }
            }
        )
    }

    fun startPurchase(activity: Activity) {
        launch {
            runCatching {
                _state.update { it.copy(isLoading = true) }
                handleBillingResult(billing.launchPurchase(activity))
                _state.update { it.copy(isLoading = false) }
            }.onFailure {
                tracker.trackError(it)
                dialogShower.showError(it.localizedMessage)
            }
        }
    }

    private fun handleBillingResult(billingResultCode: Int) {
        if (billingResultCode != BillingClient.BillingResponseCode.OK) {
            tracker.trackError(RuntimeException("Billing not available code: $billingResultCode"))
            dialogShower.showErrorById(R.string.items_not_found)
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
                work = {
                    groupActions.setGroupRingtone(
                        contactsInGroup = this@with.contacts,
                        mediaStoreUriString = this@with.ringtoneUriList.first()
                    )
                    true
                },
                onError = ::handleError,
                onSuccess = {
                    invalidateAndUpdate()
                    showInterstitialAdIfNeeded()
                }
            )
        }
    }

    fun trackNoneFatal(error: Exception): Unit = tracker.trackError(error)

    fun resetGroupRingtones() {
        showPickerLoading()
        launchOnIoResultInMain(
            work = { groupActions.clearAllRingtones(); true },
            onError = ::handleError,
            onSuccess = {
                _pickerUiState.update { it.copy(isLoading = false, shouldPop = true) }
                invalidateAndUpdate()
            }
        )
    }

    private fun invalidateAndUpdate() {
        contactsRepo.invalidate()
        updateGroupList()
    }

    private fun handleError(error: Throwable) {
        tracker.trackError(error)
        hideHomeLoading()
        hidePickerLoading()
        dialogShower.showError(error.localizedMessage)
    }

    // Interstitial policy: OWNED → never; NOT_OWNED/UNKNOWN → show
    private fun showInterstitialAdIfNeeded() {
        when (state.value.entitlement) {
            EntitlementState.OWNED -> {
                hideHomeLoading()
                dialogShower.showInfo(R.string.everything_set)
                return
            }
            EntitlementState.NOT_OWNED,
            EntitlementState.UNKNOWN,
                -> {
                adHelper.run {
                    loadInterstitialAd {
                        hideHomeLoading()
                        showInterstitialAd()
                    }
                }
            }
        }
    }

    private suspend fun createGroupByName(name: String, account: Account?): LabelItem =
        name.takeIf { it.isNotEmpty() }
            ?.let { noneEmptyName -> groupActions.createGroup(noneEmptyName, account) }
            ?: throw IllegalArgumentException("Group name is empty")

    private suspend fun manageGroupChange(result: PickerResultData.GroupNameChange) {
        val newName = result.newGroupName?.trim()
        if (newName.isNullOrEmpty() || newName == result.labelItem.groupName) {
            throw IllegalArgumentException("${result.newGroupName} could not be applied on ${result.labelItem}")
        }
        groupActions.renameGroup(result.labelItem.id, newName)
    }

    private suspend fun manageContacts(result: PickerResultData.ManageGroupContacts) {
        groupActions.updateGroupMembers(
            groupId = result.group.id,
            newSelected = result.selectedContacts,
            oldSelected = result.group.contacts
        )
    }

    private fun ensureAccountSelectionOrAskOnce() {
        if (SelectedAccountsStore.hasSelection(encryptedPrefs)) return
        val available = AccountsResolver.getContactsAccounts(appContext)
        when (available.size) {
            0 -> { /* do nothing */
            }
            1 -> SelectedAccountsStore.write(encryptedPrefs, available)
            else -> _events.trySend(HomeEvent.AskAccountSelection(available.toList()))
        }
    }

    fun updateGroupList() {
        // A) show snapshot immediately
        viewModelScope.launch {
            ContactsSnapshotStore.read(encryptedPrefs, accountsKey())?.let { (items, _) ->
                _state.update { it.copy(isLoading = false, labelItems = items) }
            }
        }
        // B) guard
        val now = System.currentTimeMillis()
        if (isRefreshing.get() || (now - lastRefreshAt) < refreshCooldownMs) return
        isRefreshing.set(true)

        val finalize = {
            lastRefreshAt = System.currentTimeMillis()
            isRefreshing.set(false)
        }

        // C) SWR refresh
        launchOnIoResultInMain(
            work = { contactsRepo.load(forceRefresh = false) },
            onSuccess = {
                val fresh = contactsRepo.labelsFlow.value
                ContactsSnapshotStore.write(
                    encryptedPrefs,
                    accountsKey(),
                    fresh,
                )
                _state.update { it.copy(isLoading = false, labelItems = fresh) }
                finalize()
            },
            onError = { err ->
                handleError(err)
                finalize()
            }
        )
    }

    private fun setPickerLoadingForResult(result: PickerResultData? = null) {
        _pickerUiState.update { it.copy(isLoading = true, pikerResultData = result) }
    }

    private fun showHomeLoading(): Unit =
        _state.update { it.copy(isLoading = true) }

    private fun hideHomeLoading(): Unit =
        _state.update { it.copy(isLoading = false) }

    private fun showPickerLoading(): Unit =
        _pickerUiState.update { it.copy(isLoading = true) }

    private fun hidePickerLoading(): Unit =
        _pickerUiState.update { it.copy(isLoading = false) }

    private fun getContactPickerData(group: LabelItem, allContacts: List<Contact> = emptyList()) =
        PickerResultData.ManageGroupContacts(
            group = group,
            selectedContacts = group.contacts,
            allContacts = allContacts
        )
}