package com.milen.grounpringtonesetter.ui.home.viewmodel

import android.app.Activity
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.BillingClient
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.billing.BillingEntitlementManager
import com.milen.grounpringtonesetter.billing.BillingError
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.ui.ads.AdLoadingHelper
import com.milen.grounpringtonesetter.customviews.ui.ads.InterstitialAdShowResult
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.accounts.AccountId
import com.milen.grounpringtonesetter.data.accounts.AccountRepository
import com.milen.grounpringtonesetter.data.exceptions.DeleteLabelException
import com.milen.grounpringtonesetter.data.exceptions.DeleteLabelFailureReason
import com.milen.grounpringtonesetter.data.prefs.HomePreferencesStore
import com.milen.grounpringtonesetter.data.repos.ContactsRepository
import com.milen.grounpringtonesetter.ui.home.HomeDisplayPreferences
import com.milen.grounpringtonesetter.ui.home.HomeEvent
import com.milen.grounpringtonesetter.ui.home.HomeScreenState
import com.milen.grounpringtonesetter.ui.home.deriveVisibleLabelItems
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import com.milen.grounpringtonesetter.utils.RingtoneFormatValidator
import com.milen.grounpringtonesetter.utils.Tracker
import com.milen.grounpringtonesetter.utils.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

internal class HomeViewModel(
    private val adHelper: AdLoadingHelper,
    private val tracker: Tracker,
    private val billing: BillingEntitlementManager,
    private val contactsRepo: ContactsRepository,
    private val accountRepo: AccountRepository,
    private val homePreferencesStore: HomePreferencesStore,
) : ViewModel() {
    private companion object {
        const val PURCHASE_UI_GUARD_TIMEOUT_MS = 180_000L
    }

    private val purchaseStartGuard = AtomicBoolean(false)
    private var purchaseUiGuardTimeoutJob: Job? = null

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
            val filteredLabels = deriveVisibleLabelItems(
                labels = labels,
                groupSearchQuery = base.groupSearchQuery,
                sortOption = base.displayPreferences.groupSortOption
            )
            base.copy(
                isLoading = base.isLoading,
                labelItems = filteredLabels,
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

    init {
        loadHomeDisplayPreferences()
    }

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
        tracker.trackEvent(
            "on_connection_changed",
            mapOf("is_online" to isOnline)
        )
        if (!isOnline && state.value.entitlement != EntitlementState.OWNED) {
            launch { _events.send(HomeEvent.ConnectionLost) }
        }
    }

    fun onSelectAccountClicked() =
        showAccountPicker(accountRepo.getAccountsAvailable())

    fun onUserPreferencesClicked() {
        tracker.trackEvent("home_user_preferences_opened")
    }

    suspend fun persistHomeDisplayPreferences(preferences: HomeDisplayPreferences): Boolean =
        saveHomeDisplayPreferencesIfChanged(
            current = _state.value.displayPreferences,
            updated = preferences,
            store = homePreferencesStore,
            onStateUpdated = { updatedPreferences ->
                _state.update { it.copy(displayPreferences = updatedPreferences) }
            },
            trackEvent = tracker::trackEvent,
            trackError = tracker::trackError
        )

    fun onGroupSearchQueryUpdated(query: String) {
        _state.update { it.copy(groupSearchQuery = query) }
    }

    fun onGroupSearchVisibilityChanged(isVisible: Boolean) {
        _state.update { state ->
            if (isVisible) {
                state.copy(isGroupSearchVisible = true)
            } else {
                state.copy(isGroupSearchVisible = false, groupSearchQuery = "")
            }
        }
    }

    fun onAccountsSelected(selected: AccountId?) {
        tracker.trackEvent(
            "on_accounts_selected",
            mapOf(
                "has_account" to (selected != null),
                "account_sig" to accountSignature(selected)
            )
        )
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
                    refreshContactsSilently()
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

    fun onRingtoneChosen(
        activity: Activity,
        uri: Uri,
        fileName: String,
        shouldValidateFormat: Boolean = true,
    ) {
        tracker.trackEvent("onRingtoneChosen")
        adHelper.updateActivity(activity)
        val group = _selectingGroup ?: return

        if (group.contacts.isEmpty()) {
            launch { _events.send(HomeEvent.ShowErrorById(R.string.no_contacts)) }
            _selectingGroup = null
            return
        }

        // Validate ringtone format before processing
        viewModelScope.launch {
            if (activity.isDestroyed) {
                tracker.trackEvent("ringtone_validation_activity_unavailable")
                _selectingGroup = null
                return@launch
            }
            
            if (shouldValidateFormat) {
                val errorResId = withContext(DispatchersProvider.io) {
                    RingtoneFormatValidator.validateRingtoneFormat(
                        context = activity,
                        uri = uri
                    )
                }

                if (errorResId != null) {
                    val mimeType = runCatching { activity.contentResolver.getType(uri) }.getOrNull() ?: "unknown"
                    tracker.trackEvent(
                        "ringtone_format_rejected",
                        mapOf(
                            "mime_type" to mimeType,
                            "file_name" to fileName
                        )
                    )
                    _events.send(HomeEvent.ShowErrorById(errorResId))
                    _selectingGroup = null
                    return@launch
                }
            }

            showLoading()
            try {
                contactsRepo.setGroupRingtone(
                    group = group,
                    uriStr = uri.toString(),
                    fileName = fileName
                )
                _selectingGroup = null
                showInterstitialAdIfNeededAndManageLoading()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                handleError(t)
            } finally {
                hideLoading()
            }
        }
    }

    fun setUpGroupNameEditing(group: LabelItem) {
        tracker.trackEvent("setUpGroupNameEditing")
        _events.trySend(HomeEvent.NavigateToRename(group))
    }

    fun setUpContactsManaging(group: LabelItem) {
        tracker.trackEvent("setUpContactsManaging")
        _events.trySend(HomeEvent.NavigateToManageContacts(group))
    }

    fun setUpGroupCreateRequest() {
        tracker.trackEvent("setUpGroupCreateRequest")
        accountRepo.getAccountsAvailable()
        _events.trySend(HomeEvent.NavigateToCreateGroup)
    }

    fun onDeviceDefaultTonesClicked() {
        tracker.trackEvent("onDeviceDefaultTonesClicked")
        _events.trySend(HomeEvent.NavigateToDeviceDefaultTones)
    }

    fun startPurchase(activity: Activity) {
        if (!purchaseStartGuard.compareAndSet(false, true)) {
            tracker.trackEvent("billing_purchase_ui_ignored_already_in_progress")
            return
        }
        _state.update { it.copy(isPurchaseInProgress = true) }

        launch {
            val startTime = System.currentTimeMillis()
            _state.update { it.copy(isLoading = true) }
            var waitForResumeToRelease = false
            tracker.trackEvent(
                "billing_purchase_ui_started",
                mapOf("activity" to activity::class.java.simpleName)
            )

            try {
                val result = billing.launchPurchase(activity)
                tracker.trackEvent(
                    "billing_purchase_ui_completed",
                    mapOf(
                        "rc" to result,
                        "elapsed_ms" to (System.currentTimeMillis() - startTime)
                    )
                )
                handleBillingResult(result)
                if (result == BillingClient.BillingResponseCode.OK) {
                    waitForResumeToRelease = true
                    startPurchaseUiGuardTimeout()
                }
            } catch (e: CancellationException) {
                tracker.trackEvent(
                    "billing_purchase_ui_cancelled",
                    mapOf("elapsed_ms" to (System.currentTimeMillis() - startTime))
                )
                throw e
            } catch (e: Throwable) {
                tracker.trackEvent(
                    "billing_purchase_ui_exception",
                    mapOf(
                        "error_type" to e::class.java.simpleName,
                        "error_message" to (e.localizedMessage ?: "unknown"),
                        "elapsed_ms" to (System.currentTimeMillis() - startTime)
                    )
                )
                tracker.trackError(e)
                _events.trySend(HomeEvent.ShowErrorText(e.localizedMessage ?: "Purchase failed"))
            } finally {
                _state.update { it.copy(isLoading = false) }
                if (!waitForResumeToRelease) {
                    releasePurchaseUiGuard("purchase_flow_finished")
                }
            }
        }
    }

    fun onHomeResumed(activity: Activity) {
        adHelper.updateActivity(activity)
        if (_state.value.isPurchaseInProgress) {
            releasePurchaseUiGuard("home_resumed")
        }
    }

    private fun startPurchaseUiGuardTimeout() {
        purchaseUiGuardTimeoutJob?.cancel()
        purchaseUiGuardTimeoutJob = viewModelScope.launch {
            delay(PURCHASE_UI_GUARD_TIMEOUT_MS)
            releasePurchaseUiGuard("timeout")
        }
    }

    private fun releasePurchaseUiGuard(reason: String) {
        purchaseUiGuardTimeoutJob?.cancel()
        purchaseUiGuardTimeoutJob = null
        if (purchaseStartGuard.compareAndSet(true, false)) {
            tracker.trackEvent("billing_purchase_ui_guard_release", mapOf("reason" to reason))
        }
        _state.update { it.copy(isPurchaseInProgress = false) }
    }

    private fun handleBillingResult(code: Int) {
        val billingError = BillingError.fromResponseCode(code)
        val errorMsg = when {
            code == BillingClient.BillingResponseCode.OK -> {
                tracker.trackEvent("billing_result_ok")
                null
            }
            code == BillingClient.BillingResponseCode.USER_CANCELED -> {
                tracker.trackEvent("billing_result_user_canceled")
                null
            }
            code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                tracker.trackEvent(
                    "billing_result_item_already_owned",
                    mapOf("error_category" to billingError.category.name)
                )
                R.string.item_not_available
            }
            billingError.category == BillingError.ErrorCategory.CONFIGURATION -> {
                tracker.trackEvent(
                    "billing_result_configuration_error",
                    mapOf(
                        "rc" to code,
                        "rc_name" to rcName(code),
                        "error_category" to billingError.category.name
                    )
                )
                when (code) {
                    BillingClient.BillingResponseCode.DEVELOPER_ERROR -> R.string.billing_service_unavailable
                    BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> R.string.billing_product_not_found
                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> R.string.billing_configuration_error
                    else -> R.string.billing_configuration_error
                }
            }
            billingError.category == BillingError.ErrorCategory.TEMPORARY -> {
                tracker.trackEvent(
                    "billing_result_temporary_error",
                    mapOf(
                        "rc" to code,
                        "rc_name" to rcName(code),
                        "error_category" to billingError.category.name
                    )
                )
                when (code) {
                    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> R.string.billing_connection_timeout
                    else -> R.string.billing_temporary_unavailable
                }
            }
            else -> {
                tracker.trackEvent(
                    "billing_result_fatal_error",
                    mapOf(
                        "rc" to code,
                        "rc_name" to rcName(code),
                        "error_category" to billingError.category.name
                    )
                )
                R.string.purchase_unavailable
            }
        }

        errorMsg?.let { _events.trySend(HomeEvent.ShowInfoText(it)) }
    }

    private fun rcName(code: Int) = when (code) {
        BillingClient.BillingResponseCode.OK -> "OK"
        BillingClient.BillingResponseCode.USER_CANCELED -> "USER_CANCELED"
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE"
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "ITEM_UNAVAILABLE"
        BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
        BillingClient.BillingResponseCode.ERROR -> "ERROR"
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED"
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
        else -> "UNKNOWN_$code"
    }

    private fun accountSignature(accountId: AccountId?): String {
        val raw = accountId?.raw.orEmpty()
        if (raw.isBlank()) return "none"
        return raw.hashCode().toUInt().toString(16)
    }

    private fun updateGroupList() {
        launch {
            showLoading()

            runCatching { contactsRepo.loadAccountLabelsShallow() }
                .onFailure { error ->
                    handleError(error)
                }
                .onSuccess {
                    enrichRingtonesInBackground()
                }
            
            hideLoading()
        }
    }


    private var ringtoneEnrichmentJob: Job? = null
    private fun enrichRingtonesInBackground() {
        ringtoneEnrichmentJob?.cancel()
        ringtoneEnrichmentJob = launch {
            try {
                contactsRepo.enrichRingtonesForCurrentLabels(batchSize = 100)
            } catch (_: CancellationException) {
                // ignored
            } catch (t: Throwable) {
                tracker.trackError(t)
            }
        }
    }


    private fun ensureAccountSelectionOrAskOnce() {
        if (!_state.value.arePermissionsGranted) return

        // If we already have a selection, just ensure data loading happens.
        if (accountRepo.selected.value != null) {
            updateGroupList()
            return
        }

        // Fallback: if repo hasn't filled yet, do a direct resolver read (permission is granted now)
        val deviceAccounts = accountRepo.getAccountsAvailable()
        when (deviceAccounts.size) {
            0 -> {
                _state.update { it.copy(isLoading = false) }
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
            val messageResId = when (error) {
                is DeleteLabelException -> when (error.reason) {
                    DeleteLabelFailureReason.GROUP_PROTECTED -> R.string.group_cannot_be_deleted
                    DeleteLabelFailureReason.DELETE_FAILED -> R.string.something_went_wrong
                }
                else -> R.string.something_went_wrong
            }
            _events.trySend(HomeEvent.ShowErrorById(messageResId))
        }
    }

    private var refreshJob: Job? = null
    private fun refreshContactsSilently() {
        refreshJob?.cancel()
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
            EntitlementState.NOT_OWNED ->
                adHelper.showInterstitialAd { result ->
                    hideLoading()
                    when (result) {
                        InterstitialAdShowResult.SHOWN -> showDoneMessage()
                        InterstitialAdShowResult.LOAD_FAILED,
                        InterstitialAdShowResult.SHOW_FAILED -> {
                            tracker.trackEvent(
                                "ringtone_interstitial_unavailable",
                                mapOf("reason" to result.name.lowercase())
                            )
                            _events.trySend(HomeEvent.ShowAdUnavailableDialog)
                        }
                    }
                }

            EntitlementState.UNKNOWN, EntitlementState.PENDING ->
                adHelper.showInterstitialAd {
                    hideLoading()
                    showDoneMessage()
                }
        }

        refreshContactsSilently()
    }

    private fun showDoneMessage() {
        _events.trySend(HomeEvent.ShowInfoText(R.string.everything_set))
    }

    private fun loadHomeDisplayPreferences() {
        viewModelScope.launch {
            runCatching { homePreferencesStore.read() }
                .onSuccess { preferences ->
                    _state.update { it.copy(displayPreferences = preferences) }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    tracker.trackError(error)
                }
        }
    }
}
