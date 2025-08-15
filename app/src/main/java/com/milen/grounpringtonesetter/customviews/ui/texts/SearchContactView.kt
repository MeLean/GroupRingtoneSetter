package com.milen.grounpringtonesetter.customviews.ui.texts

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.SearchView
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.SelectableContact
import com.milen.grounpringtonesetter.data.SelectableContact.Companion.toContact
import com.milen.grounpringtonesetter.databinding.CustomSelectableContactsViewBinding
import com.milen.grounpringtonesetter.screens.picker.ContactsAdapter

internal class SearchContactView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private var contactsList: List<SelectableContact> = emptyList()
    private var filteredContactsList: List<SelectableContact> = emptyList()
    private val contactsAdapter: ContactsAdapter = ContactsAdapter()

    private val binding = CustomSelectableContactsViewBinding.inflate(
        LayoutInflater.from(context), this, true
    )

    val selectedContacts: List<Contact>
        get() = contactsList
            .filter { it.isChecked }
            .map { it.toContact() }

    init {
        orientation = VERTICAL
        with(binding) {
            contactsRecyclerView.adapter = contactsAdapter

            searchView.setOnQueryTextListener(
                object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String?): Boolean = true

                    override fun onQueryTextChange(newText: String?): Boolean {
                        filteredContactsList = if (newText.isNullOrEmpty()) {
                            contactsList
                        } else {
                            contactsList.filter { it.name.contains(newText, ignoreCase = true) }
                        }
                        contactsAdapter.submitList(filteredContactsList)
                        return true
                    }
                }
            )
        }
    }


    fun submitContacts(contacts: List<SelectableContact>) {
        this.contactsList = contacts
        this.filteredContactsList = contacts
        contactsAdapter.submitList(filteredContactsList)
    }
}