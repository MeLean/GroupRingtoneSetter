package com.milen.grounpringtonesetter.ui.backup

import com.milen.grounpringtonesetter.backup.BackupExportResult
import com.milen.grounpringtonesetter.backup.RestoreExecutionResult
import com.milen.grounpringtonesetter.backup.RestorePlan
import com.milen.grounpringtonesetter.backup.RestorePreview
import com.milen.grounpringtonesetter.backup.RestorePreviewGroupTone

internal object BackupRestoreSummaryText {
    fun buildExportSummary(
        format: String,
        result: BackupExportResult,
    ): String = String.format(
        format,
        result.groupToneCount,
        result.defaultToneCount,
        result.assetCount
    )

    fun buildRestorePreview(
        format: String,
        groupToneLineFormat: String,
        preview: RestorePreview,
        systemSettingsPermissionNote: String? = null,
    ): String {
        val summary = preview.summary
        val header = String.format(
            format,
            summary.groupsToApply,
            summary.defaultTonesToApply,
            summary.audioFilesToRestore
        )
        val groupLines = preview.groupTones.joinToString(separator = "\n") { groupTone ->
            String.format(groupToneLineFormat, groupTone.groupName, groupTone.toneDisplayName)
        }
        val permissionNote = systemSettingsPermissionNote
            ?.takeIf { summary.defaultTonesToApply > 0 }
        return listOf(header, groupLines, permissionNote)
            .filterNot { it.isNullOrBlank() }
            .joinToString(separator = "\n\n")
    }

    fun buildRestoreResult(
        format: String,
        result: RestoreExecutionResult,
    ): String = String.format(
        format,
        result.groupsProcessed,
        result.contactsUpdated,
        result.defaultTonesApplied,
        result.missingGroups,
        result.failedTones
    )
}

internal fun RestorePlan.toPreview(): RestorePreview =
    RestorePreview(
        summary = summary,
        groupTones = groupTones.map { groupTone ->
            RestorePreviewGroupTone(
                groupName = groupTone.groupName,
                toneDisplayName = groupTone.toneDisplayName
            )
        }
    )
