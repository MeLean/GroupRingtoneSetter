package com.milen.grounpringtonesetter.ui.home

import com.milen.grounpringtonesetter.data.LabelItem
import java.text.Collator
import java.util.Locale

internal fun deriveVisibleLabelItems(
    labels: List<LabelItem>,
    groupSearchQuery: String,
    sortOption: GroupSortOption,
    locale: Locale = Locale.getDefault(),
): List<LabelItem> {
    val normalizedQuery = groupSearchQuery.normalizeForHomeSearch(locale)
    val filteredLabels = if (normalizedQuery.isBlank()) {
        labels
    } else {
        labels.filter { label ->
            label.groupName.normalizeForHomeSearch(locale).contains(normalizedQuery)
        }
    }

    return when (sortOption) {
        GroupSortOption.CURRENT_ORDER -> filteredLabels
        GroupSortOption.ALPHABETICAL_ASC ->
            filteredLabels.sortedWith(buildLabelComparator(locale, isDescending = false))

        GroupSortOption.ALPHABETICAL_DESC ->
            filteredLabels.sortedWith(buildLabelComparator(locale, isDescending = true))
    }
}

private fun buildLabelComparator(
    locale: Locale,
    isDescending: Boolean,
): Comparator<LabelItem> {
    val collator = Collator.getInstance(locale).apply {
        strength = Collator.SECONDARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }

    return Comparator { first, second ->
        val firstName = first.groupName.trim()
        val secondName = second.groupName.trim()
        val byName = collator.compare(firstName, secondName)
        when {
            byName == 0 -> first.id.compareTo(second.id)
            isDescending -> -byName
            else -> byName
        }
    }
}

internal fun String.normalizeForHomeSearch(
    locale: Locale = Locale.getDefault(),
): String = trim().lowercase(locale)
