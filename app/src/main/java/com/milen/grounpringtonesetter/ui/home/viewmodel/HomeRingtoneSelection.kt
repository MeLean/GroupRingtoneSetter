package com.milen.grounpringtonesetter.ui.home.viewmodel

import com.milen.grounpringtonesetter.data.LabelItem

internal fun resolveSelectedGroupForRingtone(
    labels: List<LabelItem>,
    selectedGroupId: Long?,
): LabelItem? {
    val groupId = selectedGroupId ?: return null
    return labels.firstOrNull { it.id == groupId }
}
