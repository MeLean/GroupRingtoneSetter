package com.milen.grounpringtonesetter.ui.picker

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.SelectableContact
import com.milen.grounpringtonesetter.databinding.ItemContactBinding
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import com.milen.grounpringtonesetter.utils.currentThemeAppearance
import com.milen.grounpringtonesetter.utils.getFileNameOrEmpty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal class ContactsAdapter :
    ListAdapter<SelectableContact, ContactsAdapter.ViewHolder>(DiffCallback) {

    private var onContactCheckedStateChanged: (SelectableContact) -> Unit = {}
    private var targetGroupName: String = ""

    // Thread-safe in-memory caches
    private val labelCache = ConcurrentHashMap<String, String>()
    private val inFlight = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // Background scope for resolving labels
    private val ioScope = CoroutineScope(SupervisorJob() + DispatchersProvider.io)

    init {
        setHasStableIds(true)
    }
    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        ioScope.coroutineContext.cancelChildren()
    }

    inner class ViewHolder(private val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: SelectableContact, onChecked: (SelectableContact) -> Unit) =
            with(binding) {
                val themeAppearance = root.context.currentThemeAppearance()
                ctvContactName.text = contact.name
                val phoneText = PickerContactAccessibilityText.buildPhoneText(
                    phoneLabel = root.context.getString(R.string.phone_label),
                    phoneNumber = contact.phone,
                    labelWithTextFormat = root.context.getString(R.string.accessibility_label_with_text)
                )
                ctvContactPhone.text = phoneText
                ctvContactPhone.isVisible = phoneText.isNotBlank()
                bindRingtoneLabel(contact)

                checkbox.apply {
                    setOnCheckedChangeListener(null)
                    isChecked = contact.isChecked
                    contentDescription = buildCheckboxDescription(contact, contact.isChecked)
                    buttonTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(
                            context,
                            themeAppearance.actionButtonBackgroundColorRes
                        )
                    )
                    setOnCheckedChangeListener { _, checked ->
                        contentDescription = buildCheckboxDescription(contact, checked)
                        onChecked(contact.copy(isChecked = checked))
                    }
                }
            }

        private fun buildCheckboxDescription(
            contact: SelectableContact,
            isChecked: Boolean,
        ): String = PickerContactAccessibilityText.buildCheckboxDescription(
            isChecked = isChecked,
            contactName = contact.name,
            groupName = targetGroupName,
            includeTemplate = binding.root.context.getString(R.string.include_contact_in_group),
            removeTemplate = binding.root.context.getString(R.string.remove_contact_from_group)
        )

        fun bindRingtoneLabel(contact: SelectableContact): Unit = with(binding) {
            val uri = contact.ringtoneUriString ?: ""
            // Fast path: cache
            labelCache[uri]?.let { cached ->
                ctvRingTone.text = formatLabel(cached)
                return
            }
            // Nothing cached yet → optimistic blank
            ctvRingTone.text = ""

            if (uri.isBlank() || !inFlight.add(uri)) return

            // Resolve off-main; then update this holder directly if still bound to same item
            ioScope.launch {
                try {
                    val ctx = root.context.applicationContext
                    val name = withContext(DispatchersProvider.io) {
                        runCatching { uri.toUri().getFileNameOrEmpty(ctx) }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                    }
                    if (!name.isNullOrBlank()) {
                        labelCache[uri] = name
                        withContext(Dispatchers.Main) {
                            // still bound to same item & same uri?
                            val pos = adapterPosition
                            if (pos != RecyclerView.NO_POSITION) {
                                val current = this@ContactsAdapter.currentList.getOrNull(pos)
                                if (current != null &&
                                    current.id == contact.id &&
                                    current.ringtoneUriString == uri
                                ) {
                                    ctvRingTone.text = formatLabel(name)
                                }
                            }
                        }
                    }
                } finally {
                    inFlight.remove(uri)
                }
            }
        }

        private fun formatLabel(name: String): String =
            "${binding.root.context.getString(R.string.ringtone_lable)}:\u00A0$name"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position)) { updated -> onContactCheckedStateChanged(updated) }
    }

    fun submitListWithCallback(
        list: List<SelectableContact>?,
        onContactCheckedStateChanged: (SelectableContact) -> Unit,
    ) {
        this.onContactCheckedStateChanged = onContactCheckedStateChanged
        val locale = java.util.Locale.getDefault()
        super.submitList(list?.let { contacts -> sortSelectableContacts(contacts, locale) })
    }

    fun updateTargetGroupName(groupName: String) {
        targetGroupName = groupName
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SelectableContact>() {
        override fun areItemsTheSame(o: SelectableContact, n: SelectableContact) = o.id == n.id
        override fun areContentsTheSame(o: SelectableContact, n: SelectableContact) = o == n
    }
}
