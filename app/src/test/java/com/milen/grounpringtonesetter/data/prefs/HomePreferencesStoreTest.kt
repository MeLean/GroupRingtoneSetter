package com.milen.grounpringtonesetter.data.prefs

import com.milen.grounpringtonesetter.ui.home.GroupSortOption
import com.milen.grounpringtonesetter.ui.home.HomeDisplayPreferences
import com.milen.grounpringtonesetter.ui.home.HomeThemeOption
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePreferencesStoreTest {

    @Test
    fun `read returns defaults when values are missing`() = runTest {
        val store = HomePreferencesStore(FakeHomePreferencesDataSource())

        assertEquals(HomeDisplayPreferences(), store.read())
    }

    @Test
    fun `read falls back to defaults when values are invalid`() = runTest {
        val store = HomePreferencesStore(
            FakeHomePreferencesDataSource(
                initialValues = mapOf(
                    "home_theme_option" to "NOT_A_THEME",
                    "home_group_sort_option" to "NOT_A_SORT"
                )
            )
        )

        assertEquals(HomeDisplayPreferences(), store.read())
    }

    @Test
    fun `write persists preferences for later reads`() = runTest {
        val dataSource = FakeHomePreferencesDataSource()
        val store = HomePreferencesStore(dataSource)
        val expected = HomeDisplayPreferences(
            themeOption = HomeThemeOption.LIGHT_HIGH_CONTRAST,
            groupSortOption = GroupSortOption.ALPHABETICAL_DESC
        )

        store.write(expected)

        assertEquals(expected, store.read())
    }
}

private class FakeHomePreferencesDataSource(
    initialValues: Map<String, String> = emptyMap(),
) : HomePreferencesDataSource {

    private val values = initialValues.toMutableMap()

    override suspend fun getString(key: String, defaultValue: String?): String? =
        values[key] ?: defaultValue

    override suspend fun saveString(key: String, value: String) {
        values[key] = value
    }
}
