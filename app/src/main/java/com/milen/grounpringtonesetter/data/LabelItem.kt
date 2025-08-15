package com.milen.grounpringtonesetter.data

internal data class LabelItem(
    val id: Long,
    val groupName: String,
    val contacts: List<Contact>,
    val ringtoneUriList: List<String> = emptyList(),
    val ringtoneFileName: String = "",
)
