package com.milen.grounpringtonesetter.customviews.ui.texts

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.SelectableContact
import com.milen.grounpringtonesetter.data.SelectableContact.Companion.toContact
import com.milen.grounpringtonesetter.databinding.CustomSelectableContactsViewBinding
import com.milen.grounpringtonesetter.ui.home.HomeThemeAppearance
import com.milen.grounpringtonesetter.ui.picker.ContactsAdapter
import com.milen.grounpringtonesetter.utils.currentThemeAppearance

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

    fun setBulkActionClickListener(listener: () -> Unit) {
        binding.selectAllUngroupedButton.setOnClickListener(listener)
    }

    fun showBulkAction(
        @StringRes textResId: Int,
        count: Int,
        isEnabled: Boolean,
    ) {
        binding.selectAllUngroupedButton.apply {
            isVisible = true
            setText(context.getString(textResId, count))
            setButtonEnabled(isEnabled)
        }
    }

    init {
        orientation = VERTICAL
        with(binding) {
            contactsRecyclerView.adapter = contactsAdapter
            emptyState.isVisible = false

            val focusSearchInput = {
                searchView.isIconified = false
                searchView.requestFocusFromTouch()
                searchView.findViewById<EditText>(
                    androidx.appcompat.R.id.search_src_text
                )?.let { searchInput ->
                    searchInput.requestFocusFromTouch()
                    searchInput.setSelection(searchInput.text?.length ?: 0)
                    val inputMethodManager = ContextCompat.getSystemService(
                        context,
                        InputMethodManager::class.java
                    )
                    inputMethodManager?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }

            searchCard.setOnClickListener { focusSearchInput() }
            searchView.setOnClickListener { focusSearchInput() }
            searchView.findViewById<View>(
                androidx.appcompat.R.id.search_plate
            )?.setOnClickListener { focusSearchInput() }

            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = true
                override fun onQueryTextChange(newText: String?): Boolean {
                    currentQuery = newText.orEmpty()
                    applyFilterAndSubmit()
                    return true
                }
            })
        }

        applyTheme(context.currentThemeAppearance())
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

    fun applyTheme(themeAppearance: HomeThemeAppearance) {
        val surfaceBackgroundColor = ContextCompat.getColor(
            context,
            themeAppearance.surfaceBackgroundColorRes
        )
        val textColor = ContextCompat.getColor(context, themeAppearance.textColorRes)
        val iconTintColor = ContextCompat.getColor(context, themeAppearance.iconTintColorRes)
        val hintColor = ContextCompat.getColor(context, themeAppearance.searchHintColorRes)

        binding.searchCard.setCardBackgroundColor(surfaceBackgroundColor)
        binding.searchCard.strokeColor = ContextCompat.getColor(
            context,
            themeAppearance.searchStrokeColorRes
        )
        binding.searchCard.strokeWidth = (resources.displayMetrics.density * 1.5f).toInt()
        binding.contactsRecyclerView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.emptyState.setTextColor(textColor)
        TextViewCompat.setCompoundDrawableTintList(
            binding.emptyState,
            ColorStateList.valueOf(iconTintColor)
        )

        binding.searchView.findViewById<EditText>(
            androidx.appcompat.R.id.search_src_text
        )?.apply {
            setTextColor(textColor)
            setHintTextColor(hintColor)
        }
        binding.searchView.findViewById<View>(
            androidx.appcompat.R.id.search_plate
        )?.background = null

        listOf(
            androidx.appcompat.R.id.search_mag_icon,
            androidx.appcompat.R.id.search_close_btn,
            androidx.appcompat.R.id.search_go_btn,
            androidx.appcompat.R.id.search_voice_btn
        ).forEach { viewId ->
            binding.searchView.findViewById<ImageView>(viewId)?.imageTintList =
                ColorStateList.valueOf(iconTintColor)
        }
    }
}
