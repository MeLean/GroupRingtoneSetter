package com.milen.grounpringtonesetter.ui.picker

import com.milen.grounpringtonesetter.data.SelectableContact
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class PickerContactSortingTest {

    @Test
    fun `sortSelectableContacts keeps checked contacts first`() {
        val result = sortSelectableContacts(
            contacts = listOf(
                selectableContact(id = 2, name = "Bob", isChecked = false),
                selectableContact(id = 3, name = "Adam", isChecked = true),
                selectableContact(id = 1, name = "Carol", isChecked = false)
            ),
            locale = Locale.GERMAN
        )

        assertEquals(listOf(3L, 2L, 1L), result.map { contact -> contact.id })
    }

    @Test
    fun `sortSelectableContacts ignores case for alphabetical order`() {
        val result = sortSelectableContacts(
            contacts = listOf(
                selectableContact(id = 2, name = "Bundesbahn"),
                selectableContact(id = 1, name = "bank99")
            ),
            locale = Locale.GERMAN
        )

        assertEquals(listOf(1L, 2L), result.map { contact -> contact.id })
    }

    @Test
    fun `sortSelectableContacts uses locale aware umlaut order`() {
        val result = sortSelectableContacts(
            contacts = listOf(
                selectableContact(id = 2, name = "Verein"),
                selectableContact(id = 1, name = "ÖBB")
            ),
            locale = Locale.GERMAN
        )

        assertEquals(listOf(1L, 2L), result.map { contact -> contact.id })
    }

    @Test
    fun `sortSelectableContacts falls back to id when names are equivalent`() {
        val result = sortSelectableContacts(
            contacts = listOf(
                selectableContact(id = 2, name = " Alpha "),
                selectableContact(id = 1, name = "alpha")
            ),
            locale = Locale.ENGLISH
        )

        assertEquals(listOf(1L, 2L), result.map { contact -> contact.id })
    }

    private fun selectableContact(
        id: Long,
        name: String,
        isChecked: Boolean = false,
    ) = SelectableContact(
        id = id,
        lookupKey = "lookup-$id",
        name = name,
        phone = null,
        ringtoneUriString = null,
        isChecked = isChecked
    )
}
