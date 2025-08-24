package com.milen.grounpringtonesetter.ui.home.viewmodel

import android.app.Activity
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.BillingClient
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.billing.BillingEntitlementManager
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.ui.ads.AdLoadingHelper
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.accounts.AccountId
import com.milen.grounpringtonesetter.data.accounts.AccountRepository
import com.milen.grounpringtonesetter.data.repos.ContactsRepository
import com.milen.grounpringtonesetter.ui.home.HomeEvent
import com.milen.grounpringtonesetter.ui.home.HomeScreenState
import com.milen.grounpringtonesetter.utils.DefaultDispatcherProvider
import com.milen.grounpringtonesetter.utils.DispatcherProvider
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import com.milen.grounpringtonesetter.utils.Tracker
import com.milen.grounpringtonesetter.utils.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
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

internal class HomeViewModel(
    private val adHelper: AdLoadingHelper,
    private val tracker: Tracker,
    private val billing: BillingEntitlementManager,
    private val contactsRepo: ContactsRepository,
    private val accountRepo: AccountRepository,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) : ViewModel() {

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    private val _state = MutableStateFlow(HomeScreenState(isLoading = true))
    val state: StateFlow<HomeScreenState> =
        combine(
            _state,
            billing.state,
            accountRepo.selected,
            accountRepo.available,
            contactsRepo.labelsFlow
        ) { base, entitlement, selectedAcc, availableAccounts, labels ->
            base.copy(
                isLoading = base.isLoading,
                labelItems = labels,
                entitlement = entitlement,
                selectedAccount = selectedAcc,
                canChangeAccount = availableAccounts.size > 1,
                loadingVisible = base.arePermissionsGranted && base.isLoading || entitlement == EntitlementState.UNKNOWN
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = _state.value
        )

    private var _selectingGroup: LabelItem? = null
    var selectingGroup: LabelItem
        get() = _selectingGroup
            ?: throw UninitializedPropertyAccessException("_selectingGroup not initialized")
        set(value) {
            _selectingGroup = value
        }

    fun onPermissionsGranted() {
        tracker.trackEvent("onPermissionsGranted")
        accountRepo.refreshAvailable()
        if (!_state.value.arePermissionsGranted) {
            _state.update { it.copy(arePermissionsGranted = true) }
        }

        ensureAccountSelectionOrAskOnce()
    }

    fun onNoPermissions() {
        _state.update { it.copy(arePermissionsGranted = false) }
    }

    fun onPermissionsRefused() {
        _state.update { it.copy(isLoading = false) }
        launch {
            _events.trySend(HomeEvent.ShowErrorById(R.string.need_permission_to_run))
            tracker.trackEvent("onPermissionsRefused")
        }
    }

    fun onConnectionChanged(isOnline: Boolean) {
        if (!isOnline && state.value.entitlement != EntitlementState.OWNED) {
            tracker.trackEvent("onConnectionChanged")
            launch { _events.send(HomeEvent.ConnectionLost) }
        }
    }

    fun onSelectAccountClicked() =
        showAccountPicker(accountRepo.getAccountsAvailable())

    fun onAccountsSelected(selected: AccountId?) {
        tracker.trackEvent("onAccountsSelected", mapOf("account" to "$selected"))
        selected?.let {
            showLoading()
            viewModelScope.launch {
                val result = runCatching {
                    withContext(DispatchersProvider.io) {
                        accountRepo.selectNewAccount(selected)
                    }
                }
                result.onSuccess {
                    updateGroupList()
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    handleError(e)
                }
            }
        } ?: run {
            tracker.trackError(RuntimeException("Account selected with null"))
            _events.trySend(HomeEvent.ShowErrorById(R.string.something_went_wrong))
        }
    }

    fun onGroupDeleted(labelItem: LabelItem) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(DispatchersProvider.io) {
                    contactsRepo.deleteGroup(labelItem.id)
                }
            }
            result.onSuccess {
                showDoneMessage()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                handleError(e)
            }
        }
    }

    fun onRingtoneChosen(uri: Uri, fileName: String) {
        tracker.trackEvent("onRingtoneChosen")
        val group = _selectingGroup ?: return

        if (group.contacts.isEmpty()) {
            launch { _events.send(HomeEvent.ShowErrorById(R.string.no_contacts)) }
            _selectingGroup = null
            return
        }

        showLoading()

        viewModelScope.launch {
            val result = runCatching {
                withContext(DispatchersProvider.io) {
                    contactsRepo.setGroupRingtone(
                        group = group,
                        uriStr = uri.toString(),
                        fileName = fileName
                    )
                }
            }
            result.onSuccess {
                _selectingGroup = null
                showInterstitialAdIfNeededAndManageLoading()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                handleError(e)
            }
        }
    }

    fun setUpGroupNameEditing(group: LabelItem) {
        tracker.trackEvent("setUpGroupNameEditing")
        viewModelScope.launch { _events.send(HomeEvent.NavigateToRename(group)) }
    }

    fun setUpContactsManaging(group: LabelItem) {
        tracker.trackEvent("setUpContactsManaging")
        viewModelScope.launch { _events.send(HomeEvent.NavigateToManageContacts(group)) }
    }

    fun setUpGroupCreateRequest() {
        tracker.trackEvent("setUpGroupCreateRequest")
        accountRepo.getAccountsAvailable()
        viewModelScope.launch {
            _events.send(HomeEvent.NavigateToCreateGroup)
        }
    }

    fun startPurchase(activity: Activity) {
        launch {
            runCatching {
                _state.update { it.copy(isLoading = true) }
                handleBillingResult(billing.launchPurchase(activity))
                _state.update { it.copy() }
            }.onFailure {
                tracker.trackError(it)
                _events.trySend(HomeEvent.ShowErrorText(it.localizedMessage))
            }
        }
    }

    private fun handleBillingResult(code: Int) {
        if (code != BillingClient.BillingResponseCode.OK) {
            tracker.trackError(RuntimeException("Billing not available code: $code"))
            _events.trySend(HomeEvent.ShowErrorById(R.string.items_not_found))
        }
    }

    private fun updateGroupList() {
        launch {
            showLoading()

            runCatching { contactsRepo.loadAccountLabels() }
                .onFailure { error ->
                    handleError(error)
                }

            hideLoading()
        }
    }


    private fun ensureAccountSelectionOrAskOnce() {
        if (!_state.value.arePermissionsGranted) return

        // If we already have a selection, just ensure data loading happens.
        if (accountRepo.selected.value != null) {
            updateGroupList()
            refreshContactsSilently()
            return
        }

        // Fallback: if repo hasn't filled yet, do a direct resolver read (permission is granted now)
        val deviceAccounts = accountRepo.getAccountsAvailable()
        when (deviceAccounts.size) {
            0 -> {
                _state.update { it.copy(isLoading = false) }
                _events.trySend(HomeEvent.ShowErrorById(R.string.items_not_found))
            }

            1 -> {
                accountRepo.selectNewAccount(deviceAccounts.first())
                updateGroupList()
            }

            else -> {
                _state.update { it.copy(isLoading = false) }
                showAccountPicker(accounts = deviceAccounts)
            }
        }
    }

    private fun showAccountPicker(accounts: Set<AccountId>) {
        if (accounts.isEmpty()) {
            _events.trySend(HomeEvent.ShowErrorById(R.string.items_not_found))
            return
        }
        _events.trySend(HomeEvent.AskAccountSelection(accounts, accountRepo.selected.value))
    }

    private fun showLoading() = _state.update { it.copy(isLoading = true) }
    private fun hideLoading() = _state.update { it.copy(isLoading = false) }

    private fun handleError(error: Throwable) {
        tracker.trackError(error)
        hideLoading()
        launch {
            _events.trySend(HomeEvent.ShowErrorText(error.localizedMessage))
        }
    }

    private var refreshJob: Job? = null
    private fun refreshContactsSilently() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try {
                contactsRepo.refreshAllPhoneContacts() // suspend call
            } catch (_: CancellationException) {
                // ignore
            } catch (e: Throwable) {
                tracker.trackError(e)
            }
        }
    }

    private fun showInterstitialAdIfNeededAndManageLoading() {
        when (state.value.entitlement) {
            EntitlementState.OWNED -> {
                hideLoading()
                showDoneMessage()
            }
            EntitlementState.NOT_OWNED, EntitlementState.UNKNOWN -> adHelper.run {
                loadInterstitialAd {
                    hideLoading()
                    showDoneMessage()
                    showInterstitialAd()
                }
            }
        }

        refreshContactsSilently()
    }

    private fun showDoneMessage() {
        _events.trySend(HomeEvent.ShowInfoText(R.string.everything_set))
    }
}