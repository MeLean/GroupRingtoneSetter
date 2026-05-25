package com.milen.grounpringtonesetter.ui.home.viewmodel

import com.milen.grounpringtonesetter.data.sources.ContactSource

internal sealed interface CreateGroupSourceResolution {
    data object UseSelectedSource : CreateGroupSourceResolution
    data object NoSourcesAvailable : CreateGroupSourceResolution
    data class AutoSelectSingleSource(val source: ContactSource) : CreateGroupSourceResolution
    data class AskUserToSelectSource(val sources: Set<ContactSource>) : CreateGroupSourceResolution
}

internal fun resolveCreateGroupSourceResolution(
    selectedSource: ContactSource?,
    availableSources: Set<ContactSource>,
): CreateGroupSourceResolution {
    val validSelectedSource = selectedSource?.takeIf { current -> current in availableSources }

    return when {
        validSelectedSource != null -> CreateGroupSourceResolution.UseSelectedSource
        availableSources.isEmpty() -> CreateGroupSourceResolution.NoSourcesAvailable
        availableSources.size == 1 ->
            CreateGroupSourceResolution.AutoSelectSingleSource(availableSources.first())

        else -> CreateGroupSourceResolution.AskUserToSelectSource(availableSources)
    }
}
