package com.milen.grounpringtonesetter.ui.home

import androidx.annotation.StringRes
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.sources.ContactSource

internal data class HomeScreenState(
    val isLoading: Boolean = false,
    val labelItems: List<LabelItem> = emptyList(),
    val displayPreferences: HomeDisplayPreferences = HomeDisplayPreferences(),
    val groupSearchQuery: String = "",
    val isGroupSearchVisible: Boolean = false,
    val arePermissionsGranted: Boolean = false,
    val scrollToBottom: Boolean = false,
    val entitlement: EntitlementState = EntitlementState.UNKNOWN,
    val selectedSource: ContactSource? = null,
    val hasContactsInSelectedSource: Boolean? = null,
    val canChangeSource: Boolean = true,
    val isPurchaseInProgress: Boolean = false,
    val loadingVisible: Boolean = false,
)

internal sealed interface HomeEvent {
    object ConnectionLost : HomeEvent
    data object NavigateToCreateGroup : HomeEvent
    data object NavigateToDeviceDefaultTones : HomeEvent
    data object ShowAdUnavailableDialog : HomeEvent
    data class ShowErrorById(@param:StringRes val strRes: Int) : HomeEvent
    data class ShowInfoText(@param:StringRes val strRes: Int) : HomeEvent
    data class ShowErrorText(val message: String?) : HomeEvent
    data class AskSourceSelection(
        val sources: Set<ContactSource>,
        val selected: ContactSource? = null,
    ) : HomeEvent
    data class NavigateToRename(val group: LabelItem) : HomeEvent
    data class NavigateToManageContacts(val group: LabelItem) : HomeEvent
}
