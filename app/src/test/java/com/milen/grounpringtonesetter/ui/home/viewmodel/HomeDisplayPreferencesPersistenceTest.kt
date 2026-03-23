package com.milen.grounpringtonesetter.ui.home.viewmodel

import com.milen.grounpringtonesetter.data.prefs.HomePreferencesDataSource
import com.milen.grounpringtonesetter.data.prefs.HomePreferencesStore
import com.milen.grounpringtonesetter.ui.home.GroupSortOption
import com.milen.grounpringtonesetter.ui.home.HomeDisplayPreferences
import com.milen.grounpringtonesetter.ui.home.HomeThemeOption
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDisplayPreferencesPersistenceTest {

    @Test
    fun `saveHomeDisplayPreferencesIfChanged skips work when unchanged`() = runTest {
        val current = HomeDisplayPreferences()
        val savedStates = mutableListOf<HomeDisplayPreferences>()
        val trackedEvents = mutableListOf<String>()
        val trackedErrors = mutableListOf<Throwable>()
        val store = HomePreferencesStore(FakeRecordingHomePreferencesDataSource())

        val changed = saveHomeDisplayPreferencesIfChanged(
            current = current,
            updated = current,
            store = store,
            onStateUpdated = savedStates::add,
            trackEvent = { eventName, _ -> trackedEvents += eventName },
            trackError = trackedErrors::add
        )

        assertFalse(changed)
        assertTrue(savedStates.isEmpty())
        assertTrue(trackedEvents.isEmpty())
        assertTrue(trackedErrors.isEmpty())
    }

    @Test
    fun `saveHomeDisplayPreferencesIfChanged updates state tracks and persists`() = runTest {
        val current = HomeDisplayPreferences()
        val updated = HomeDisplayPreferences(
            themeOption = HomeThemeOption.DARK_HIGH_CONTRAST,
            groupSortOption = GroupSortOption.ALPHABETICAL_ASC
        )
        val savedStates = mutableListOf<HomeDisplayPreferences>()
        val trackedEvents = mutableListOf<Pair<String, Map<String, Any>?>>()
        val trackedErrors = mutableListOf<Throwable>()
        val dataSource = FakeRecordingHomePreferencesDataSource()
        val store = HomePreferencesStore(dataSource)

        val changed = saveHomeDisplayPreferencesIfChanged(
            current = current,
            updated = updated,
            store = store,
            onStateUpdated = savedStates::add,
            trackEvent = { eventName, params -> trackedEvents += eventName to params },
            trackError = trackedErrors::add
        )

        assertTrue(changed)
        assertEquals(listOf(updated), savedStates)
        assertEquals(1, trackedEvents.size)
        assertEquals("home_user_preferences_saved", trackedEvents.single().first)
        assertEquals(updated.themeOption.name, trackedEvents.single().second?.get("theme_option"))
        assertEquals(
            updated.groupSortOption.name,
            trackedEvents.single().second?.get("group_sort_option")
        )
        assertTrue(trackedErrors.isEmpty())
        assertEquals(
            listOf("home_theme_option" to updated.themeOption.name, "home_group_sort_option" to updated.groupSortOption.name),
            dataSource.savedEntries
        )
    }

    @Test
    fun `saveHomeDisplayPreferencesIfChanged tracks write failure after updating state`() = runTest {
        val current = HomeDisplayPreferences()
        val updated = HomeDisplayPreferences(themeOption = HomeThemeOption.LIGHT_HIGH_CONTRAST)
        val expectedError = IllegalStateException("write failed")
        val savedStates = mutableListOf<HomeDisplayPreferences>()
        val trackedEvents = mutableListOf<String>()
        val trackedErrors = mutableListOf<Throwable>()
        val store = HomePreferencesStore(
            FakeRecordingHomePreferencesDataSource(failure = expectedError)
        )

        val changed = saveHomeDisplayPreferencesIfChanged(
            current = current,
            updated = updated,
            store = store,
            onStateUpdated = savedStates::add,
            trackEvent = { eventName, _ -> trackedEvents += eventName },
            trackError = trackedErrors::add
        )

        assertTrue(changed)
        assertEquals(listOf(updated), savedStates)
        assertEquals(listOf("home_user_preferences_saved"), trackedEvents)
        assertEquals(1, trackedErrors.size)
        assertSame(expectedError, trackedErrors.single())
    }
}

private class FakeRecordingHomePreferencesDataSource(
    private val failure: Throwable? = null,
) : HomePreferencesDataSource {

    val savedEntries = mutableListOf<Pair<String, String>>()

    override suspend fun getString(key: String, defaultValue: String?): String? = defaultValue

    override suspend fun saveString(key: String, value: String) {
        failure?.let { throw it }
        savedEntries += key to value
    }
}
