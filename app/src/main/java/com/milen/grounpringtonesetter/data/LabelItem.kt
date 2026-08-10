package com.milen.grounpringtonesetter.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

internal enum class LabelStorageKind {
    PROVIDER_GROUP,
    LOCAL_STORE,
}

@Parcelize
internal data class LabelItem(
    val id: String,
    val groupName: String,
    val contacts: List<Contact>,
    val ringtoneUriList: List<String> = emptyList(),
    val ringtoneFileName: String = "",
    val isReadOnly: Boolean = false,
    val canModify: Boolean = true,
    val canDelete: Boolean = true,
    val storageKind: LabelStorageKind = LabelStorageKind.PROVIDER_GROUP,
    val providerGroupId: Long? = null,
) : Parcelable
