package com.milen.grounpringtonesetter.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
internal data class Contact(
    val id: Long,
    val name: String,
    val phone: String?,
    val ringtoneUriStr: String?,
) : Parcelable
