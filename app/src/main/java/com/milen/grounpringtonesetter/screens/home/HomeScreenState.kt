package com.milen.grounpringtonesetter.screens.home

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
    data object ConnectionLost : HomeEvent
}