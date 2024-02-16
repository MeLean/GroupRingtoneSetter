package com.milen.grounpringtonesetter.data

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class GroupItem(
    val id: Long,
    val groupName: String,
    val contacts: List<Contact>,
    var ringtoneUri: Uri? = null
) : Parcelable
