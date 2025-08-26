package com.milen.grounpringtonesetter.ui.home

import androidx.annotation.StringRes
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.accounts.AccountId

internal data class HomeScreenState(
    val isLoading: Boolean = false,
    val labelItems: List<LabelItem> = emptyList(),
    val arePermissionsGranted: Boolean = true,
    val scrollToBottom: Boolean = false,
    val entitlement: EntitlementState = EntitlementState.UNKNOWN,
    val accountPickerAccounts: List<String>? = null,
    val selectedAccount: AccountId? = null,
    val canChangeAccount: Boolean = true,
    val loadingVisible: Boolean = false,
)

internal sealed interface HomeEvent {
    object ConnectionLost : HomeEvent
    data object NavigateToCreateGroup : HomeEvent
    data class ShowErrorById(@param:StringRes val strRes: Int) : HomeEvent
    data class ShowInfoText(@param:StringRes val strRes: Int) : HomeEvent
    data class ShowErrorText(val message: String?) : HomeEvent
    data class AskAccountSelection(
        val accounts: Set<AccountId>,
        val selected: AccountId? = null,
    ) : HomeEvent
    data class NavigateToRename(val group: LabelItem) : HomeEvent
    data class NavigateToManageContacts(val group: LabelItem) : HomeEvent
}