package com.milen.grounpringtonesetter.ui.picker

import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem

internal fun findUngroupedContacts(
    allContacts: List<Contact>,
    labels: List<LabelItem>,
): List<Contact> {
    if (allContacts.isEmpty()) return emptyList()

    val groupedContactIds = labels
        .asSequence()
        .flatMap { label -> label.contacts.asSequence() }
        .map { contact -> contact.id }
        .toHashSet()

    return allContacts.filterNot { contact -> contact.id in groupedContactIds }
}

internal fun countSelectableUngroupedContacts(
    ungroupedContacts: List<Contact>,
    selectedContacts: List<Contact>,
): Int {
    if (ungroupedContacts.isEmpty()) return 0
    val selectedIds = selectedContacts.mapTo(hashSetOf()) { contact -> contact.id }
    return ungroupedContacts.count { contact -> contact.id !in selectedIds }
}

internal fun addUngroupedContactsToSelection(
    selectedContacts: List<Contact>,
    ungroupedContacts: List<Contact>,
): List<Contact> {
    if (ungroupedContacts.isEmpty()) return selectedContacts.distinctBy { contact -> contact.id }

    val selectedIds = selectedContacts.mapTo(hashSetOf()) { contact -> contact.id }
    val contactsToAdd = ungroupedContacts.filterNot { contact -> contact.id in selectedIds }
    return (selectedContacts + contactsToAdd).distinctBy { contact -> contact.id }
}
