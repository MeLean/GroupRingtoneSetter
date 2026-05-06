package com.milen.grounpringtonesetter.ui.home

import com.milen.grounpringtonesetter.R

internal fun resolveHomeEmptyStateMessageRes(state: HomeScreenState): Int =
    when {
        shouldShowHomeEmptyAddGroupButton(state) ->
            R.string.on_device_groups_not_found

        else -> R.string.items_not_found
    }

internal fun shouldShowHomeEmptyAddGroupButton(state: HomeScreenState): Boolean =
    state.selectedSource != null && state.hasContactsInSelectedSource == true
