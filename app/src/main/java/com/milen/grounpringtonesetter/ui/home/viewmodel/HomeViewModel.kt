package com.milen.grounpringtonesetter.ui.home.viewmodel

import android.app.Activity
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.BillingClient
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.actions.GroupActions
import com.milen.grounpringtonesetter.billing.BillingEntitlementManager
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.ui.ads.AdLoadingHelper
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.accounts.AccountsResolver
import com.milen.grounpringtonesetter.data.cache.ContactsSnapshotStore
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.data.prefs.SelectedAccountsStore
import com.milen.grounpringtonesetter.data.repos.ContactsRepository
import com.milen.grounpringtonesetter.ui.home.HomeEvent
import com.milen.grounpringtonesetter.ui.home.HomeScreenState
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.Tracker
import com.milen.grounpringtonesetter.utils.launch
import com.milen.grounpringtonesetter.utils.launchOnIoResultInMain
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
import java.util.concurrent.atomic.AtomicBoolean

internal class HomeViewModel(
    private val appContext: App,
    private val adHelper: AdLoadingHelper,
    private val contactsHelper: ContactsHelper,
    private val encryptedPrefs: EncryptedPreferencesHelper,
    private val tracker: Tracker,
    private val billing: BillingEntitlementManager,
    private val contactsRepo: ContactsRepository,
    private val actions: GroupActions,
    private val contactsStore: ContactsSnapshotStore = ContactsSnapshotStore,
) : ViewModel() {

    private fun accountsKey(): String = SelectedAccountsStore.accountsKeyOrAll(encryptedPrefs)
    private val isRefreshing = AtomicBoolean(false)
    private var lastRefreshAt = 0L
    private val refreshCooldownMs = 2000L

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    private val _state = MutableStateFlow(HomeScreenState(isLoading = true))
    val state: StateFlow<HomeScreenState> =
        combine(_state, billing.state) { base, entitlement ->
            base.copy(
                entitlement = entitlement,
                isLoading = base.isLoading || entitlement == EntitlementState.UNKNOWN
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
        _state.tryEmit(_state.value.copy(isLoading = false, arePermissionsGranted = false))
    }

    fun onPermissionsRefused() {
        launch {
            _events.trySend(HomeEvent.ShowErrorById(R.string.need_permission_to_run))
                .also { tracker.trackEvent("onPermissionsRefused") }
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
        _state.value = _state.value.copy(isLoading = true, arePermissionsGranted = true)
        ensureAccountSelectionOrAskOnce()
        if (SelectedAccountsStore.hasSelection(encryptedPrefs)) updateGroupList()
    }

    fun onAccountsSelected(selected: Set<String>?) {
        if (selected.isNullOrEmpty()) {
            ensureAccountSelectionOrAskOnce(); return
        }
        SelectedAccountsStore.write(encryptedPrefs, selected)
        invalidateAndUpdate()
    }


    fun onGroupDeleted(labelItem: LabelItem) {
        showHomeLoading()
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

        showHomeLoading()

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

                invalidateAndUpdate()

                applyRingtoneToGroupInState(group.id, uriStr, fileName)

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
        val accounts = contactsHelper.getGoogleAccounts()
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
                _state.update { it.copy(isLoading = false) }
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
            s.copy(isLoading = false, labelItems = updated)
        }

        // 2) Persist the snapshot right away so the next cold read shows this state
        launch {
            ContactsSnapshotStore.write(
                encryptedPrefs,
                accountsKey(),
                state.value.labelItems,           // includes the update above
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

    fun updateCachedContactsData() {
        viewModelScope.launch {
            contactsStore.read(encryptedPrefs, accountsKey())?.let { (items, _) ->
                _state.update { it.copy(isLoading = false, labelItems = items) }
            }
        }
    }

    fun updateGroupList() {
        updateCachedContactsData()

        // Guard
        val now = System.currentTimeMillis()
        if (isRefreshing.get() || (now - lastRefreshAt) < refreshCooldownMs) return
        isRefreshing.set(true)

        val finalize = {
            lastRefreshAt = System.currentTimeMillis()
            isRefreshing.set(false)
        }

        launchOnIoResultInMain(
            work = { contactsRepo.load(forceRefresh = false) },
            onSuccess = { fresh ->
                contactsStore.write(
                    encryptedPrefs,
                    accountsKey(),
                    fresh,
                )
                if (_state.value.labelItems != fresh) {
                    _state.update { it.copy(isLoading = false, labelItems = fresh) }
                }
                finalize()
            },
            onError = { err -> handleError(err); finalize() }
        )
    }

    private fun invalidateAndUpdate() {
        contactsRepo.invalidate()
        updateGroupList()
    }

    private fun ensureAccountSelectionOrAskOnce() {
        if (SelectedAccountsStore.hasSelection(encryptedPrefs)) return
        val available = AccountsResolver.getContactsAccounts(appContext)
        when (available.size) {
            0 -> Unit
            1 -> SelectedAccountsStore.write(encryptedPrefs, available)
            else -> _events.trySend(HomeEvent.AskAccountSelection(available.toList()))
        }
    }

    private fun showHomeLoading() = _state.update { it.copy(isLoading = true) }
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

            EntitlementState.NOT_OWNED, EntitlementState.UNKNOWN ->
                adHelper.run {
                    loadInterstitialAd {
                        hideLoading()
                        _events.trySend(HomeEvent.ShowInfoText(R.string.everything_set))
                        showInterstitialAd()
                    }
                }
        }
    }
}