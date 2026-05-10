package com.milen.grounpringtonesetter.ui.picker

import com.milen.grounpringtonesetter.data.SelectableContact
import java.text.Collator
import java.util.Locale

internal fun sortSelectableContacts(
    contacts: List<SelectableContact>,
    locale: Locale = Locale.getDefault(),
): List<SelectableContact> {
    if (contacts.isEmpty()) return emptyList()

    val comparator = buildSelectableContactComparator(locale)
    return contacts.sortedWith(comparator)
}

private fun buildSelectableContactComparator(locale: Locale): Comparator<SelectableContact> {
    val collator = Collator.getInstance(locale).apply {
        strength = Collator.SECONDARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }

    return Comparator { first, second ->
        when {
            first.isChecked != second.isChecked -> {
                if (first.isChecked) -1 else 1
            }

            else -> {
                val byName = collator.compare(first.name.trim(), second.name.trim())
                when {
                    byName != 0 -> byName
                    else -> first.id.compareTo(second.id)
                }
            }
        }
    }
}
