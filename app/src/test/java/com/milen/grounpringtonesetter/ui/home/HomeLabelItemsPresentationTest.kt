package com.milen.grounpringtonesetter.ui.home

import com.milen.grounpringtonesetter.data.LabelItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class HomeLabelItemsPresentationTest {

    @Test
    fun `current order preserves repository order`() {
        val labels = listOf(
            labelItem(id = 4, groupName = "Zulu"),
            labelItem(id = 2, groupName = "Alpha"),
            labelItem(id = 3, groupName = "Beta")
        )

        val result = deriveVisibleLabelItems(
            labels = labels,
            groupSearchQuery = "",
            sortOption = GroupSortOption.CURRENT_ORDER,
            locale = Locale.ENGLISH
        )

        assertEquals(listOf("group:4", "group:2", "group:3"), result.map { it.id })
    }

    @Test
    fun `alphabetical ascending sorts by normalized name then id`() {
        val labels = listOf(
            labelItem(id = 3, groupName = " beta"),
            labelItem(id = 2, groupName = "Alpha"),
            labelItem(id = 1, groupName = " alpha ")
        )

        val result = deriveVisibleLabelItems(
            labels = labels,
            groupSearchQuery = "",
            sortOption = GroupSortOption.ALPHABETICAL_ASC,
            locale = Locale.ENGLISH
        )

        assertEquals(listOf("group:1", "group:2", "group:3"), result.map { it.id })
    }

    @Test
    fun `alphabetical descending sorts by normalized name`() {
        val labels = listOf(
            labelItem(id = 1, groupName = "Alpha"),
            labelItem(id = 2, groupName = "Gamma"),
            labelItem(id = 3, groupName = "Beta")
        )

        val result = deriveVisibleLabelItems(
            labels = labels,
            groupSearchQuery = "",
            sortOption = GroupSortOption.ALPHABETICAL_DESC,
            locale = Locale.ENGLISH
        )

        assertEquals(listOf("group:2", "group:3", "group:1"), result.map { it.id })
    }

    @Test
    fun `search filters before sorting`() {
        val labels = listOf(
            labelItem(id = 1, groupName = "Alpha Team"),
            labelItem(id = 2, groupName = "Beta"),
            labelItem(id = 3, groupName = "alpha")
        )

        val result = deriveVisibleLabelItems(
            labels = labels,
            groupSearchQuery = "alp",
            sortOption = GroupSortOption.ALPHABETICAL_DESC,
            locale = Locale.ENGLISH
        )

        assertEquals(listOf("group:1", "group:3"), result.map { it.id })
    }

    @Test
    fun `read only groups are hidden until the preference is enabled`() {
        val labels = listOf(
            labelItem(id = 1, groupName = "Editable"),
            labelItem(id = 2, groupName = "Read-only", isReadOnly = true),
        )

        val hidden = deriveVisibleLabelItems(
            labels = labels,
            groupSearchQuery = "",
            sortOption = GroupSortOption.CURRENT_ORDER,
            showReadOnlyGroups = false,
            locale = Locale.ENGLISH,
        )
        val shown = deriveVisibleLabelItems(
            labels = labels,
            groupSearchQuery = "",
            sortOption = GroupSortOption.CURRENT_ORDER,
            showReadOnlyGroups = true,
            locale = Locale.ENGLISH,
        )

        assertEquals(listOf("group:1"), hidden.map { it.id })
        assertEquals(listOf("group:1", "group:2"), shown.map { it.id })
    }

    @Test
    fun `alphabetical ascending uses locale aware Cyrillic order`() {
        val labels = listOf(
            labelItem(id = 1, groupName = "Ябълка"),
            labelItem(id = 2, groupName = "Бор"),
            labelItem(id = 3, groupName = "Арфа")
        )

        val result = deriveVisibleLabelItems(
            labels = labels,
            groupSearchQuery = "",
            sortOption = GroupSortOption.ALPHABETICAL_ASC,
            locale = Locale.forLanguageTag("bg")
        )

        assertEquals(listOf("group:3", "group:2", "group:1"), result.map { it.id })
    }

    @Test
    fun `alphabetical ascending respects Polish diacritics`() {
        val labels = listOf(
            labelItem(id = 1, groupName = "Żaba"),
            labelItem(id = 2, groupName = "Adam"),
            labelItem(id = 3, groupName = "Źrebak")
        )

        val result = deriveVisibleLabelItems(
            labels = labels,
            groupSearchQuery = "",
            sortOption = GroupSortOption.ALPHABETICAL_ASC,
            locale = Locale.forLanguageTag("pl")
        )

        assertEquals(listOf("group:2", "group:3", "group:1"), result.map { it.id })
    }

    @Test
    fun `alphabetical ascending uses Japanese locale order`() {
        val labels = listOf(
            labelItem(id = 1, groupName = "さくら"),
            labelItem(id = 2, groupName = "あさ"),
            labelItem(id = 3, groupName = "かさ")
        )

        val result = deriveVisibleLabelItems(
            labels = labels,
            groupSearchQuery = "",
            sortOption = GroupSortOption.ALPHABETICAL_ASC,
            locale = Locale.JAPANESE
        )

        assertEquals(listOf("group:2", "group:3", "group:1"), result.map { it.id })
    }

    private fun labelItem(
        id: Long,
        groupName: String,
        isReadOnly: Boolean = false,
    ) = LabelItem(
        id = "group:$id",
        groupName = groupName,
        contacts = emptyList(),
        isReadOnly = isReadOnly,
    )
}
