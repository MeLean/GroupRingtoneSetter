package com.milen.grounpringtonesetter.ui.home

import android.accounts.Account
import androidx.annotation.StringRes
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.data.LabelItem

internal data class HomeScreenState(
    val isLoading: Boolean = false,
    val labelItems: List<LabelItem> = emptyList(),
    val arePermissionsGranted: Boolean = true,
    val scrollToBottom: Boolean = false,
    val entitlement: EntitlementState = EntitlementState.UNKNOWN,
)

internal sealed interface HomeEvent {
    object ConnectionLost : HomeEvent
    data class ShowErrorById(@param:StringRes val strRes: Int) : HomeEvent
    data class ShowInfoText(@param:StringRes val strRes: Int) : HomeEvent
    data class ShowErrorText(val message: String?) : HomeEvent
    data class AskAccountSelection(val accounts: List<String>) : HomeEvent
    data class NavigateToRename(val group: LabelItem) : HomeEvent
    data class NavigateToManageContacts(val group: LabelItem) : HomeEvent
    data class NavigateToCreateGroup(val accounts: List<Account>) : HomeEvent
}