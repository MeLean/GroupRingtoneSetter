package com.milen.grounpringtonesetter.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.databinding.ItemGroupEntityBinding

internal class GroupsAdapter(
    private val interactor: GroupItemsInteractor
) :
    ListAdapter<LabelItem, GroupsAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(private val binding: ItemGroupEntityBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            group: LabelItem,
            interactor: GroupItemsInteractor,
        ): Unit =
            group.run {
                binding.apply {
                    ctvGroupName.text = groupName

                    contacts.size.let { contactsCount ->
                        cwtContacts.text = "$contactsCount"
                        cwtContacts.contentDescription =
                            "${binding.root.context.getString(R.string.group_contacts_count)}: $contactsCount"
                    }

                    ctwRingtone.text = group.ringtoneFileName

                    ctcibManageContacts.setOnClickListener {
                        interactor.onManageContacts(labelItem = this@run)
                    }
                    ctcibDelete.setOnClickListener {
                        interactor.onGroupDelete(labelItem = this@run)
                    }
                    ctcibEdit.setOnClickListener {
                        interactor.onEditName(labelItem = this@run)
                    }
                    crbChooseRingtone.setOnClickListener {
                        interactor.onChoseRingtoneIntent(labelItem = this@run)
                    }
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            ItemGroupEntityBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = getItem(position)
        holder.bind(group, interactor)
    }

    companion object DiffCallback : DiffUtil.ItemCallback<LabelItem>() {
        override fun areItemsTheSame(oldItem: LabelItem, newItem: LabelItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: LabelItem, newItem: LabelItem): Boolean {
            return oldItem == newItem
        }
    }

    interface GroupItemsInteractor {
        fun onManageContacts(labelItem: LabelItem)

        fun onEditName(labelItem: LabelItem)

        fun onGroupDelete(labelItem: LabelItem)

        fun onChoseRingtoneIntent(labelItem: LabelItem)
    }
}
