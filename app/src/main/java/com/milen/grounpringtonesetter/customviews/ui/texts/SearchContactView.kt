package com.milen.grounpringtonesetter.customviews.ui.texts

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.SearchView
import androidx.core.view.isVisible
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.SelectableContact
import com.milen.grounpringtonesetter.data.SelectableContact.Companion.toContact
import com.milen.grounpringtonesetter.databinding.CustomSelectableContactsViewBinding
import com.milen.grounpringtonesetter.ui.picker.ContactsAdapter

internal class SearchContactView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private var allContacts: List<SelectableContact> = emptyList()
    private var filteredContacts: List<SelectableContact> = emptyList()

    private var currentQuery: String = ""
    private val selectedIds: MutableSet<Long> = linkedSetOf()

    // only we restore checked IDs; SearchView restores its own query
    private var restoredCheckedIds: Set<Long>? = null

    private val contactsAdapter = ContactsAdapter()
    private val binding = CustomSelectableContactsViewBinding.inflate(
        LayoutInflater.from(context), this, true
    )

    private var onCheckedChangeListener: ((List<Contact>) -> Unit) = {}

    fun setOnCheckedChangeListener(listener: (List<Contact>) -> Unit) {
        onCheckedChangeListener = listener
    }

    init {
        orientation = VERTICAL
        with(binding) {
            contactsRecyclerView.adapter = contactsAdapter
            emptyState.isVisible = false

            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = true
                override fun onQueryTextChange(newText: String?): Boolean {
                    currentQuery = newText.orEmpty()
                    applyFilterAndSubmit()
                    return true
                }
            })
        }
    }

    fun submitContacts(contacts: List<SelectableContact>) {
        // apply restored checks (if any) or derive from incoming list
        if (restoredCheckedIds != null) {
            selectedIds.clear()
            selectedIds.addAll(restoredCheckedIds!!)
            restoredCheckedIds = null
        } else {
            selectedIds.clear()
            selectedIds.addAll(contacts.filter { it.isChecked }.map { it.id })
        }

        // never mutate items: rebuild with copies reflecting selection
        allContacts = contacts.map { it.copy(isChecked = it.id in selectedIds) }

        // use whatever text SearchView currently shows (including restored text)
        currentQuery = binding.searchView.query?.toString().orEmpty()
        applyFilterAndSubmit()
    }

    private fun applyFilterAndSubmit() {
        filteredContacts = filter(allContacts, currentQuery)
        contactsAdapter.submitListWithCallback(
            filteredContacts
        ) { updated ->
            allContacts = allContacts.map { if (it.id == updated.id) updated else it }
            val selected = allContacts.mapNotNull { if (it.isChecked) it.toContact() else null }
            onCheckedChangeListener(selected)
        }

        binding.emptyState.isVisible = filteredContacts.isEmpty()
    }

    private fun filter(source: List<SelectableContact>, q: String): List<SelectableContact> {
        if (q.isBlank()) return source
        val needle = q.trim()
        return source.filter { c ->
            c.name.contains(needle, ignoreCase = true) ||
                    (c.phone?.contains(needle, ignoreCase = true) ?: false)
        }
    }
}
