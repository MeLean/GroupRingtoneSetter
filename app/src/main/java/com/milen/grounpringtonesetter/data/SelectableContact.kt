package com.milen.grounpringtonesetter.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize


@Parcelize
internal data class SelectableContact(
    val id: Long,
    val name: String,
    val phone: String?,
    val ringtoneUriString: String?,
    val isChecked: Boolean,
) : Parcelable {
    companion object {
        fun from(contact: Contact, isSelected: Boolean = false): SelectableContact =
            contact.run {
                SelectableContact(
                    id = id,
                    name = name,
                    phone = phone,
                    ringtoneUriString = ringtoneUriStr,
                    isChecked = isSelected
                )
            }

        fun SelectableContact.toContact(): Contact =
            Contact(
                id = id,
                name = name,
                phone = phone,
                ringtoneUriStr = ringtoneUriString,
            )
    }
}
