package com.milen.grounpringtonesetter.customviews.ui.texts

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.AbsSavedState
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.SearchView
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.SelectableContact
import com.milen.grounpringtonesetter.data.SelectableContact.Companion.toContact
import com.milen.grounpringtonesetter.databinding.CustomSelectableContactsViewBinding
import com.milen.grounpringtonesetter.ui.picker.ContactsAdapter

internal class SearchContactView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private var contactsList: List<SelectableContact> = emptyList()
    private var filteredContactsList: List<SelectableContact> = emptyList()
    private val contactsAdapter: ContactsAdapter = ContactsAdapter()

    // restored state placeholders (applied when we have data)
    private var restoredQuery: String? = null
    private var restoredCheckedIds: Set<Long>? = null

    private val binding = CustomSelectableContactsViewBinding.inflate(
        LayoutInflater.from(context), this, true
    )

    val selectedContacts: List<Contact>
        get() = contactsList.filter { it.isChecked }.map { it.toContact() }

    init {
        orientation = VERTICAL
        isSaveEnabled = true // ensure View state is saved/restored by framework

        with(binding) {
            contactsRecyclerView.adapter = contactsAdapter

            searchView.setOnQueryTextListener(
                object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String?): Boolean = true

                    override fun onQueryTextChange(newText: String?): Boolean {
                        filteredContactsList = if (newText.isNullOrEmpty()) {
                            contactsList
                        } else {
                            contactsList.filter {
                                it.toString().contains(newText, ignoreCase = true)
                            }
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
        applyRestoredStateIfPossible() // <- in case restore happened before data arrived

        contactsAdapter.submitListWithCallback(filteredContactsList) { selectedContact ->
            contactsList.updateCheckedState(selectedContact)
            filteredContactsList.updateCheckedState(selectedContact)
        }
    }

    // ---- state saving/restoring ----
    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        val query = binding.searchView.query?.toString().orEmpty()
        val checkedIds = contactsList.asSequence()
            .filter { it.isChecked }
            .map { it.id }
            .toList()
        return SavedState(superState, query, checkedIds)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            restoredQuery = state.query
            restoredCheckedIds = state.checkedIds.toSet()
            applyRestoredStateIfPossible()
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    private fun applyRestoredStateIfPossible() {
        // Restore checks first (both lists share the same element instances)
        restoredCheckedIds?.let { ids ->
            if (contactsList.isNotEmpty()) {
                contactsList.forEach { it.isChecked = it.id in ids }
                // Don't clear after apply; let next submitContacts also apply if needed
            }
        }
        // Restore query (this triggers filtering via the listener)
        restoredQuery?.let { q ->
            binding.searchView.setQuery(q, false) // updates text, doesn't submit search
        }
    }

    // Parcelable holder for our view state
    private class SavedState : AbsSavedState {
        val query: String
        val checkedIds: List<Long>

        constructor(superState: Parcelable?, query: String, checkedIds: List<Long>) : super(
            superState
        ) {
            this.query = query
            this.checkedIds = checkedIds
        }

        constructor(src: Parcel, loader: ClassLoader?) : super(src, loader) {
            query = src.readString() ?: ""
            val size = src.readInt()
            val list = ArrayList<Long>(size)
            repeat(size) { list.add(src.readLong()) }
            checkedIds = list
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeString(query)
            out.writeInt(checkedIds.size)
            checkedIds.forEach { out.writeLong(it) }
        }

        companion object {
            @JvmField
            @Suppress("ParcelCreator")
            val CREATOR: Parcelable.ClassLoaderCreator<SavedState> =
                object : Parcelable.ClassLoaderCreator<SavedState> {
                    override fun createFromParcel(
                        source: Parcel,
                        loader: ClassLoader?,
                    ): SavedState =
                        SavedState(source, loader)

                    override fun createFromParcel(source: Parcel): SavedState =
                        SavedState(source, null)

                    override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
                }
        }
    }
}

private fun List<SelectableContact>.updateCheckedState(selectedContact: SelectableContact) =
    map { if (it.id == selectedContact.id) it.isChecked = selectedContact.isChecked }
