package com.milen.grounpringtonesetter.backup

internal object RestoreDefaultTonePermissionPolicy {
    fun shouldRequestWriteSettings(
        defaultToneCount: Int,
        canWriteSystemSettings: Boolean,
    ): Boolean = defaultToneCount > 0 && !canWriteSystemSettings

    fun skippedDefaultToneCount(
        defaultToneCount: Int,
        canWriteSystemSettings: Boolean,
    ): Int = if (shouldRequestWriteSettings(defaultToneCount, canWriteSystemSettings)) {
        defaultToneCount
    } else {
        0
    }
}
