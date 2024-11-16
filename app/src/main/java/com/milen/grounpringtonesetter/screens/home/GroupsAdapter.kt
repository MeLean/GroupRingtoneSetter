package com.milen.grounpringtonesetter.screens.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.milen.grounpringtonesetter.data.GroupItem
import com.milen.grounpringtonesetter.databinding.ItemGroupEntityBinding

internal class GroupsAdapter(
    private val interactor: GroupItemsInteractor
) :
    ListAdapter<GroupItem, GroupsAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(private val binding: ItemGroupEntityBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            group: GroupItem,
            interactor: GroupItemsInteractor
        ): Unit =
            group.run {
                binding.apply {
                    ctvGroupName.text = groupName
                    cwtContacts.text = "${contacts.size}"
                    ctwRingtone.text = group.ringtoneFileName

                    ctcibManageContacts.setOnClickListener {
                        interactor.onManageContacts(groupItem = this@run)
                    }
                    ctcibDelete.setOnClickListener {
                        interactor.onGroupDelete(groupItem = this@run)
                    }
                    ctcibEdit.setOnClickListener {
                        interactor.onEditName(groupItem = this@run)
                    }
                    crbSerRingtone.setOnClickListener {
                        interactor.onChoseRingtoneIntent(groupItem = this@run)
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

    companion object DiffCallback : DiffUtil.ItemCallback<GroupItem>() {
        override fun areItemsTheSame(oldItem: GroupItem, newItem: GroupItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: GroupItem, newItem: GroupItem): Boolean {
            return oldItem == newItem
        }
    }

    interface GroupItemsInteractor {
        fun onManageContacts(groupItem: GroupItem)

        fun onEditName(groupItem: GroupItem)

        fun onGroupDelete(groupItem: GroupItem)

        fun onChoseRingtoneIntent(groupItem: GroupItem)
    }
}
