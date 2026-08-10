package com.milen.grounpringtonesetter.ui.home.viewmodel

import com.milen.grounpringtonesetter.data.prefs.HomePreferencesStore
import com.milen.grounpringtonesetter.ui.home.HomeDisplayPreferences
import kotlin.coroutines.cancellation.CancellationException

internal suspend fun saveHomeDisplayPreferencesIfChanged(
    current: HomeDisplayPreferences,
    updated: HomeDisplayPreferences,
    store: HomePreferencesStore,
    onStateUpdated: (HomeDisplayPreferences) -> Unit,
    trackEvent: (String, Map<String, Any>?) -> Unit,
    trackError: (Throwable) -> Unit,
): Boolean {
    if (current == updated) return false

    onStateUpdated(updated)
    trackEvent(
        "home_user_preferences_saved",
        mapOf(
            "theme_option" to updated.themeOption.name,
            "group_sort_option" to updated.groupSortOption.name,
            "show_read_only_groups" to updated.showReadOnlyGroups,
        )
    )

    runCatching { store.write(updated) }
        .onFailure { error ->
            if (error is CancellationException) throw error
            trackError(error)
        }

    return true
}
