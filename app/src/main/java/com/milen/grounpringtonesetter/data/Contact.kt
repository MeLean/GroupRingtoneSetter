package com.milen.grounpringtonesetter.data

internal data class Contact(
    val id: Long,
    val name: String,
    val phone: String?,
    val ringtoneUriStr: String?,
)
