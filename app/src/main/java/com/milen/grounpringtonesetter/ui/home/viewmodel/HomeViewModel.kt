package com.milen.grounpringtonesetter.ui.home.viewmodel

import android.app.Activity
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.BillingClient
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.actions.GroupActions
import com.milen.grounpringtonesetter.billing.BillingEntitlementManager
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.ui.ads.AdLoadingHelper
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.accounts.AccountId
import com.milen.grounpringtonesetter.data.accounts.AccountRepository
import com.milen.grounpringtonesetter.data.accounts.selectedSetOrEmpty
import com.milen.grounpringtonesetter.data.cache.ContactsSnapshotStore
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.data.repos.ContactsRepository
import com.milen.grounpringtonesetter.ui.home.HomeEvent
import com.milen.grounpringtonesetter.ui.home.HomeScreenState
import com.milen.grounpringtonesetter.utils.Tracker
import com.milen.grounpringtonesetter.utils.launch
import com.milen.grounpringtonesetter.utils.launchOnIoResultInMain
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

internal class HomeViewModel(
    private val adHelper: AdLoadingHelper,
    private val tracker: Tracker,
    private val billing: BillingEntitlementManager,
    private val contactsRepo: ContactsRepository,
    private val actions: GroupActions,
    private val encryptedPrefs: EncryptedPreferencesHelper,
    private val contactsStore: ContactsSnapshotStore = ContactsSnapshotStore,
    private val accountRepo: AccountRepository,
) : ViewModel() {

    private fun accountsKey(): String = accountRepo.cacheKeyOrAll()

    private val refreshMutex = Mutex()
    private var refreshJob: Job? = null

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    private val _state = MutableStateFlow(HomeScreenState(isLoading = true))
    val state: StateFlow<HomeScreenState> =
        combine(
            _state,
            billing.state,
            accountRepo.selected,
            accountRepo.available,
        ) { base, entitlement, selectedAcc, availableAccounts ->
            base.copy(
                entitlement = entitlement,
                isLoading = base.isLoading,
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

    fun onPermissionsGranted() {
        tracker.trackEvent("onPermissionsGranted")
        accountRepo.refreshAvailable()
        if (!_state.value.arePermissionsGranted) {
            _state.update { it.copy(arePermissionsGranted = true) }
        }

        ensureAccountSelectionOrAskOnce()
    }

    fun onAccountsSelected(selected: Set<String>?) {
        tracker.trackEvent("onAccountsSelected", mapOf("account" to "$selected"))

        if (selected.isNullOrEmpty()) {
            // User canceled – just stop loading and remain on screen
            _state.update { it.copy(isLoading = false) }
            return
        }

        selected.firstOrNull()?.let {
            accountRepo.select(AccountId(it))
            invalidateAndUpdate(force = true)
        }
    }

    fun onGroupDeleted(labelItem: LabelItem) {
        showLoading()
        launchOnIoResultInMain(
            work = { actions.deleteGroup(labelItem.id); true },
            onError = ::handleError,
            onSuccess = { invalidateAndUpdate() }
        )
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

        val uriStr = uri.toString()

        launchOnIoResultInMain(
            work = {
                actions.setGroupRingtone(group.contacts, uriStr)
                true
            },
            onError = ::handleError,
            onSuccess = {
                if (fileName.isNotBlank()) encryptedPrefs.saveString(uriStr, fileName)

                _selectingGroup = null

                // Keep UI snappy: apply to state immediately and persist snapshot
                applyRingtoneToGroupInState(group.id, uriStr, fileName)

                // Then kick a cancellable refresh
                invalidateAndUpdate()

                showInterstitialAdIfNeededAndManageLoading()
            }
        )
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
        val accounts =
            contactsRepo.getGoogleAccounts() // TODO REMOVE ACCOUNTS FORM THE EVENT AND CROUP CREATING AT ALL

        accountRepo.getAccountsAvailable()
        viewModelScope.launch {
            _events.send(
                HomeEvent.NavigateToCreateGroup(
                    accounts = accounts,
                )
            )
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

    private fun applyRingtoneToGroupInState(
        groupId: Long,
        uriStr: String,
        fileName: String?,
    ) {
        _state.update { s ->
            val updated = s.labelItems.map { g ->
                if (g.id == groupId) {
                    g.copy(
                        ringtoneUriList = listOf(uriStr),
                        ringtoneFileName = fileName ?: g.ringtoneFileName,
                        contacts = g.contacts.map { c -> c.copy(ringtoneUriStr = uriStr) }
                    )
                } else g
            }
            s.copy(labelItems = updated)
        }

        // Persist the snapshot right away so the next cold read shows this state
        viewModelScope.launch {
            ContactsSnapshotStore.write(
                encryptedPrefs,
                accountsKey(),
                state.value.labelItems, // includes the update above
                System.currentTimeMillis()
            )
        }
    }

    private fun handleBillingResult(code: Int) {
        if (code != BillingClient.BillingResponseCode.OK) {
            tracker.trackError(RuntimeException("Billing not available code: $code"))
            _events.trySend(HomeEvent.ShowErrorById(R.string.items_not_found))
        }
    }

    fun updateFromCachedContactsData() {
        viewModelScope.launch {
            // Keeping the safe-call to match your current store signature
            contactsStore.read(encryptedPrefs, accountsKey())?.let { (items, _) ->
                _state.update { it.copy(labelItems = items) }
            }
        }
    }

    private fun updateGroupList(force: Boolean = false) {
        if (!force) {
            updateFromCachedContactsData()
        }

        refreshJob?.cancel()

        refreshJob = launch {
            showLoading()

            try {
                refreshMutex.withLock {
                    val fresh = withContext(Dispatchers.IO) {
                        val selected = accountRepo.selectedSetOrEmpty()
                        if (selected.isEmpty()) {
                            contactsRepo.load(forceRefresh = false)
                        } else {
                            // Scope to the selected account
                            contactsRepo.getAllLabelItemsForAccounts(selected)
                        }
                    }

                    contactsStore.write(
                        encryptedPrefs,
                        accountsKey(),
                        fresh,
                    )

                    // Update state
                    if (_state.value.labelItems != fresh) {
                        _state.update { it.copy(labelItems = fresh) }
                    } else {
                        _state.update { it.copy() }
                    }
                }
            } catch (ce: CancellationException) {
                // expected when superseded by a newer refresh; ignore
                ce.localizedMessage
            } catch (t: Throwable) {
                handleError(t)
            }

            hideLoading()
        }
    }

    fun onSelectAccountClicked() =
        showAccountPicker(accountRepo.getAccountsAvailable())

    private fun invalidateAndUpdate(force: Boolean = false) {
        contactsRepo.invalidate()
        updateGroupList(force)
    }

    private fun ensureAccountSelectionOrAskOnce() {
        if (!_state.value.arePermissionsGranted) return

        // If we already have a selection, just ensure data loading happens.
        if (accountRepo.selected.value != null) {
            invalidateAndUpdate(force = true)
            return
        }

        // Fallback: if repo hasn't filled yet, do a direct resolver read (permission is granted now)
        val available = accountRepo.getAccountsAvailable()
        when (available.size) {
            0 -> {
                _state.update { it.copy(isLoading = false) }
                _events.trySend(HomeEvent.ShowErrorById(R.string.items_not_found))
            }

            1 -> {
                accountRepo.select(AccountId(available.first()))
                invalidateAndUpdate(force = true)
            }

            else -> {
                _state.update { it.copy(isLoading = false) }
                showAccountPicker(available)
            }
        }
    }

    // --- picker trigger stays event-driven to match your current UI wiring ---
    private fun showAccountPicker(accounts: Set<String>) {
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

    private fun showInterstitialAdIfNeededAndManageLoading() {
        when (state.value.entitlement) {
            EntitlementState.OWNED -> {
                hideLoading()
                _events.trySend(HomeEvent.ShowInfoText(R.string.everything_set))
            }
            EntitlementState.NOT_OWNED, EntitlementState.UNKNOWN -> adHelper.run {
                loadInterstitialAd {
                    hideLoading()
                    _events.trySend(HomeEvent.ShowInfoText(R.string.everything_set))
                    showInterstitialAd()
                }
            }
        }
    }
}