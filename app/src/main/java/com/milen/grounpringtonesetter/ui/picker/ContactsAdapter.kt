package com.milen.grounpringtonesetter.ui.picker

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.SelectableContact
import com.milen.grounpringtonesetter.databinding.ItemContactBinding
import com.milen.grounpringtonesetter.utils.getFileNameOrEmpty

internal class ContactsAdapter :
    ListAdapter<SelectableContact, ContactsAdapter.ViewHolder>(DiffCallback) {

    private var onContactCheckedStateChanged: (SelectableContact) -> Unit = {}

    class ViewHolder(private val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            contact: SelectableContact,
            onContactChecked: () -> Unit,
            onContactCheckedStateChanged: (SelectableContact) -> Unit,
        ): Unit =
            binding.run {
                ctvContactName.text = contact.name
                ctvContactPhone.text = contact.phone
                ctvRingTone.text = binding.root.context.getFileNameForUriStr(
                    contact.ringtoneUriString
                )

                checkbox.apply {
                    setOnCheckedChangeListener(null)
                    isChecked = contact.isChecked
                    contentDescription = if (contact.isChecked) {
                        context.getString(R.string.click_to_uncheck)
                    } else {
                        context.getString(R.string.click_to_check)
                    }
                    setOnCheckedChangeListener { _, checked ->
                        contact.isChecked = checked
                        onContactCheckedStateChanged(contact)
                        onContactChecked()

                    }
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            ItemContactBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = getItem(position)
        holder.bind(
            contact, ::onContactChecked,
            onContactCheckedStateChanged = onContactCheckedStateChanged
        )
    }

    fun submitListWithCallback(
        list: List<SelectableContact>?,
        onContactCheckedStateChanged: (SelectableContact) -> Unit,
    ) {
        this.onContactCheckedStateChanged = onContactCheckedStateChanged

        super.submitList(
            list?.sortedWith(
                compareByDescending<SelectableContact> { it.isChecked }
                    .thenBy { it.name }
            )
        )
    }

    private fun onContactChecked() {
        submitList(currentList)
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SelectableContact>() {
        override fun areItemsTheSame(
            oldItem: SelectableContact,
            newItem: SelectableContact
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: SelectableContact,
            newItem: SelectableContact
        ): Boolean {
            return oldItem == newItem
        }
    }
}

private fun Context?.getFileNameForUriStr(ringtoneUriString: String?): String =
    this?.let { context ->
        ringtoneUriString?.toUri()?.getFileNameOrEmpty(context)?.let { ringtoneName ->
            "${context.getString(R.string.ringtone_lable)}:\u00A0$ringtoneName"
        }
    } ?: ""