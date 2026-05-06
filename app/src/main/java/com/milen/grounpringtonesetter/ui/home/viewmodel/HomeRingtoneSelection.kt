package com.milen.grounpringtonesetter.ui.home.viewmodel

import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import com.milen.grounpringtonesetter.data.LabelItem

internal fun resolveSelectedGroupForRingtone(
    labels: List<LabelItem>,
    selectedGroupId: String?,
): LabelItem? {
    val groupId = selectedGroupId ?: return null
    return labels.firstOrNull { it.id == groupId }
}

internal fun isUnsupportedGroupRingtoneUri(uri: Uri): Boolean {
    val rawUri = uri.toString()
    val isDefaultAlias = runCatching { RingtoneManager.isDefault(uri) }.getOrDefault(false)
    return isDefaultAlias ||
            uri.authority == Settings.AUTHORITY ||
            rawUri.startsWith("content://${Settings.AUTHORITY}/")
}
