package com.milen.grounpringtonesetter.data.prefs

import com.milen.grounpringtonesetter.ui.home.GroupSortOption
import com.milen.grounpringtonesetter.ui.home.HomeDisplayPreferences
import com.milen.grounpringtonesetter.ui.home.HomeThemeOption

internal interface HomePreferencesDataSource {
    suspend fun getString(key: String, defaultValue: String? = null): String?

    suspend fun saveString(key: String, value: String)
}

internal class EncryptedHomePreferencesDataSource(
    private val prefs: EncryptedPreferencesHelper,
) : HomePreferencesDataSource {

    override suspend fun getString(key: String, defaultValue: String?): String? =
        prefs.getStringAsync(key, defaultValue)

    override suspend fun saveString(key: String, value: String) =
        prefs.saveStringAsync(key, value)
}

internal class HomePreferencesStore(
    private val dataSource: HomePreferencesDataSource,
) {
    suspend fun read(): HomeDisplayPreferences {
        val themeOption = parseHomeThemeOption(dataSource.getString(KEY_HOME_THEME))
        val groupSortOption = parseGroupSortOption(dataSource.getString(KEY_GROUP_SORT))
        val showReadOnlyGroups = parseShowReadOnlyGroups(
            dataSource.getString(KEY_SHOW_READ_ONLY_GROUPS)
        )

        return HomeDisplayPreferences(
            themeOption = themeOption,
            groupSortOption = groupSortOption,
            showReadOnlyGroups = showReadOnlyGroups,
        )
    }

    suspend fun write(preferences: HomeDisplayPreferences) {
        dataSource.saveString(KEY_HOME_THEME, preferences.themeOption.name)
        dataSource.saveString(KEY_GROUP_SORT, preferences.groupSortOption.name)
        dataSource.saveString(KEY_SHOW_READ_ONLY_GROUPS, preferences.showReadOnlyGroups.toString())
    }
}

internal const val KEY_HOME_THEME = "home_theme_option"
internal const val KEY_GROUP_SORT = "home_group_sort_option"
internal const val KEY_SHOW_READ_ONLY_GROUPS = "home_show_read_only_groups"

internal fun parseHomeThemeOption(rawValue: String?): HomeThemeOption =
    runCatching { HomeThemeOption.valueOf(rawValue.orEmpty()) }
        .getOrDefault(HomeThemeOption.CLASSIC)

internal fun parseGroupSortOption(rawValue: String?): GroupSortOption =
    runCatching { GroupSortOption.valueOf(rawValue.orEmpty()) }
        .getOrDefault(GroupSortOption.ALPHABETICAL_ASC)

internal fun parseShowReadOnlyGroups(rawValue: String?): Boolean =
    rawValue.equals("true", ignoreCase = true)

internal fun readHomeDisplayPreferencesSync(
    prefs: EncryptedPreferencesHelper,
): HomeDisplayPreferences = HomeDisplayPreferences(
    themeOption = parseHomeThemeOption(prefs.getString(KEY_HOME_THEME)),
    groupSortOption = parseGroupSortOption(prefs.getString(KEY_GROUP_SORT)),
    showReadOnlyGroups = parseShowReadOnlyGroups(prefs.getString(KEY_SHOW_READ_ONLY_GROUPS)),
)
