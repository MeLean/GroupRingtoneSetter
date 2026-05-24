package com.milen.grounpringtonesetter.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
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

    private var themeAppearance: HomeThemeAppearance = HomeThemeOption.CLASSIC.toAppearance()

    class ViewHolder(private val binding: ItemGroupEntityBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            group: LabelItem,
            interactor: GroupItemsInteractor,
            themeAppearance: HomeThemeAppearance,
        ): Unit =
            group.run {
                binding.apply {
                    val context = root.context
                    val textColor = ContextCompat.getColor(context, themeAppearance.textColorRes)
                    val iconTintColor =
                        ContextCompat.getColor(context, themeAppearance.iconTintColorRes)
                    val actionButtonBackground =
                        ContextCompat.getColor(
                            context,
                            themeAppearance.actionButtonBackgroundColorRes
                        )
                    val actionButtonText =
                        ContextCompat.getColor(context, themeAppearance.actionButtonTextColorRes)
                    val counterBackground =
                        ContextCompat.getColor(context, themeAppearance.counterBackgroundColorRes)
                    val counterText =
                        ContextCompat.getColor(context, themeAppearance.counterTextColorRes)

                    root.setBackgroundResource(themeAppearance.groupCardBackgroundRes)
                    root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    root.contentDescription = null
                    ctvGroupName.text = groupName
                    ctvGroupName.setTextColor(textColor)
                    ctvGroupName.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    ctvGroupName.contentDescription = groupName

                    contacts.size.let { contactsCount ->
                        cwtContacts.text = "$contactsCount"
                        cwtContacts.setColors(
                            backgroundColor = counterBackground,
                            textColor = counterText
                        )
                        cwtContacts.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        cwtContacts.contentDescription =
                            HomeGroupCardAccessibilityText.buildContactsAccessibilityText(
                                contactsLabel = context.getString(R.string.contacts),
                                contactsCount = contactsCount
                            )
                        cwtContacts.isClickable = false
                        cwtContacts.isLongClickable = false
                        ViewCompat.setAccessibilityDelegate(
                            cwtContacts,
                            object : androidx.core.view.AccessibilityDelegateCompat() {
                                override fun onInitializeAccessibilityNodeInfo(
                                    host: View,
                                    info: AccessibilityNodeInfoCompat,
                                ) {
                                    super.onInitializeAccessibilityNodeInfo(host, info)
                                    info.className = TextView::class.java.name
                                }
                            }
                        )
                    }

                    val ringtoneText = HomeGroupCardAccessibilityText.resolveRingtoneText(
                        rawRingtoneText = group.ringtoneFileName,
                        noRingtoneLabel = context.getString(R.string.no_ringtone_selected)
                    )
                    val ringtoneLine = HomeGroupCardAccessibilityText.buildRingtoneDisplayText(
                        ringtoneLabel = context.getString(R.string.ringtone_lable),
                        ringtoneText = ringtoneText,
                        noRingtoneLabel = context.getString(R.string.no_ringtone_selected)
                    )
                    ctwRingtone.text = ringtoneLine
                    ctwRingtone.setTextColor(textColor)
                    ctwRingtone.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    ctwRingtone.contentDescription = ringtoneLine

                    ctcibManageContacts.setOnClickListener {
                        interactor.onManageContacts(labelItem = this@run)
                    }
                    ctcibManageContacts.applyButtonContentDescription(
                        context.getString(R.string.manage_contacts_group_name) + ": " + groupName
                    )
                    ctcibManageContacts.setIconTint(iconTintColor)
                    if (canDelete) {
                        ctcibDelete.isVisible = true
                        ctcibDelete.isEnabled = true
                        ctcibDelete.setOnClickListener {
                            interactor.onGroupDelete(labelItem = this@run)
                        }
                        ctcibDelete.applyButtonContentDescription(
                            context.getString(R.string.delete_group) + ": " + groupName
                        )
                    } else {
                        ctcibDelete.isVisible = false
                        ctcibDelete.isEnabled = false
                        ctcibDelete.setOnClickListener(null)
                    }
                    ctcibDelete.setIconTint(iconTintColor)
                    ctcibEdit.setOnClickListener {
                        interactor.onEditName(labelItem = this@run)
                    }
                    ctcibEdit.applyButtonContentDescription(
                        context.getString(R.string.edit_group_name) + ": " + groupName
                    )
                    ctcibEdit.setIconTint(iconTintColor)
                    crbChooseRingtone.setOnClickListener {
                        interactor.onChoseRingtoneIntent(labelItem = this@run)
                    }
                    crbChooseRingtone.setContentDescriptionText(
                        context.getString(R.string.choose_ringtone)
                            .replace("\n", " ") + ": " + groupName
                    )
                    crbChooseRingtone.setColors(
                        backgroundColor = actionButtonBackground,
                        textColor = actionButtonText
                    )
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
        holder.bind(group, interactor, themeAppearance)
    }

    fun updateThemeAppearance(themeAppearance: HomeThemeAppearance) {
        if (this.themeAppearance == themeAppearance) return
        this.themeAppearance = themeAppearance
        notifyDataSetChanged()
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
