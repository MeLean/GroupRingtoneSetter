package com.milen.grounpringtonesetter.ui.picker

import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PickerManageContactsSelectionTest {

    @Test
    fun `findUngroupedContacts returns contacts not present in current source groups`() {
        val contactOne = contact(id = 1, name = "Alice")
        val contactTwo = contact(id = 2, name = "Bob")
        val contactThree = contact(id = 3, name = "Carol")

        val result = findUngroupedContacts(
            allContacts = listOf(contactOne, contactTwo, contactThree),
            labels = listOf(
                label(id = "group-1", contacts = listOf(contactOne)),
                label(id = "group-2", contacts = listOf(contactThree))
            )
        )

        assertEquals(listOf(contactTwo), result)
    }

    @Test
    fun `countSelectableUngroupedContacts ignores ungrouped contacts already selected`() {
        val ungroupedContacts = listOf(
            contact(id = 2, name = "Bob"),
            contact(id = 3, name = "Carol")
        )

        val result = countSelectableUngroupedContacts(
            ungroupedContacts = ungroupedContacts,
            selectedContacts = listOf(contact(id = 2, name = "Bob"))
        )

        assertEquals(1, result)
    }

    @Test
    fun `addUngroupedContactsToSelection appends only missing ungrouped contacts`() {
        val selectedContacts = listOf(
            contact(id = 1, name = "Alice"),
            contact(id = 2, name = "Bob")
        )
        val ungroupedContacts = listOf(
            contact(id = 2, name = "Bob"),
            contact(id = 3, name = "Carol")
        )

        val result = addUngroupedContactsToSelection(
            selectedContacts = selectedContacts,
            ungroupedContacts = ungroupedContacts
        )

        assertEquals(listOf(1L, 2L, 3L), result.map { contact -> contact.id })
    }

    private fun contact(
        id: Long,
        name: String,
    ) = Contact(
        id = id,
        lookupKey = "lookup-$id",
        name = name,
        phone = null,
        ringtoneUriStr = null
    )

    private fun label(
        id: String,
        contacts: List<Contact>,
    ) = LabelItem(
        id = id,
        groupName = id,
        contacts = contacts
    )
}
