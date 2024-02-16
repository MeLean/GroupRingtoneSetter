package com.milen.grounpringtonesetter.data

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize


@Parcelize
data class Contact(
    val id: Long,
    val name: String,
    val phone: String?,
    val ringtoneUriString: String?
) : Parcelable {
    val ringtoneUri: Uri?
        get() = ringtoneUriString?.let { Uri.parse(it) }
}
