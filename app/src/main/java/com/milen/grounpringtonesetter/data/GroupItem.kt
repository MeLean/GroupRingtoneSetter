package com.milen.grounpringtonesetter.data

data class GroupItem(
    val id: Long,
    val groupName: String,
    val contacts: List<Contact>,
    val ringtoneUriStr: List<String> = emptyList(),
    val ringtoneFileName: String = ""
)
