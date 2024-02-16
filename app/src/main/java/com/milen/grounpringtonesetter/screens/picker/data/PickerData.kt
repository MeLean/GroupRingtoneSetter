package com.milen.grounpringtonesetter.screens.picker.data

import android.os.Parcelable
import androidx.annotation.StringRes
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.GroupItem
import kotlinx.parcelize.Parcelize

@Parcelize
sealed class PickerData : Parcelable {
    @get:StringRes
    abstract val titleId: Int

    @Parcelize
    data class GroupNameChange(
        override val titleId: Int,
        val groupItem: GroupItem
    ) : PickerData()

    @Parcelize
    data class ContactsPiker(
        override val titleId: Int,
        val groupItem: GroupItem,
        val allContacts: List<Contact> = emptyList()
    ) : PickerData()
}