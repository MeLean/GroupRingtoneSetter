package com.milen.grounpringtonesetter.screens.picker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.milen.grounpringtonesetter.data.SelectableContact
import com.milen.grounpringtonesetter.databinding.ItemContactBinding

internal class ContactsAdapter :
    ListAdapter<SelectableContact, ContactsAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(private val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(contact: SelectableContact, onContactChecked: () -> Unit): Unit =
            binding.run {
                ctvContactName.text = contact.name
                ctvContactPhone.text = contact.phone
                checkbox.apply {
                    setOnCheckedChangeListener(null)
                    isChecked = contact.isChecked
                    setOnCheckedChangeListener { _, checked ->
                        contact.isChecked = checked
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
        holder.bind(contact, ::onContactChecked)
    }

    override fun submitList(list: List<SelectableContact>?) {
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