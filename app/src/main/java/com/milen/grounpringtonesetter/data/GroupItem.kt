package com.milen.grounpringtonesetter.data

import android.net.Uri

data class GroupItem(
    val groupName: String,
    val contacts: List<Contact>,
    var ringtoneUri: Uri? = null
)
