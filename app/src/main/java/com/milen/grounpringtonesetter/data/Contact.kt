package com.milen.grounpringtonesetter.data

data class Contact(
    val id: Long,
    val name: String,
    val phone: String?,
    val ringtoneUriStr: String?
)
