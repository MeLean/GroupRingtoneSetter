package com.milen.grounpringtonesetter.backup

import android.net.Uri
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneType

internal const val GRS_FORMAT_ID = "com.milen.grounpringtonesetter.grs"
internal const val GRS_SCHEMA_VERSION = 1
internal const val GRS_FILE_EXTENSION = ".grs"

internal data class GrsBackupSnapshot(
    val createdAtEpochMillis: Long,
    val appVersionName: String,
    val sourceLabel: String,
    val groupTones: List<GrsGroupToneSnapshot>,
    val defaultTones: List<GrsDefaultToneSnapshot>,
    val tones: List<GrsToneSnapshot>,
)

internal data class GrsGroupToneSnapshot(
    val groupName: String,
    val toneId: String,
)

internal data class GrsDefaultToneSnapshot(
    val type: DeviceDefaultToneType,
    val toneId: String,
    val displayName: String,
)

internal data class GrsToneSnapshot(
    val id: String,
    val displayName: String,
    val category: GrsToneCategory,
    val archivePath: String,
    val sizeBytes: Long? = null,
)

internal data class GrsToneAsset(
    val toneId: String,
    val archivePath: String,
    val sourceUri: Uri,
    val sizeBytes: Long?,
)

internal enum class GrsToneCategory {
    RINGTONE,
    NOTIFICATION,
    ALARM,
}

internal data class BackupArchiveContent(
    val snapshot: GrsBackupSnapshot,
    val assetBytesByPath: Map<String, ByteArray>,
)

internal data class BackupBuildResult(
    val snapshot: GrsBackupSnapshot,
    val assets: List<GrsToneAsset>,
)

internal data class BackupExportResult(
    val groupToneCount: Int,
    val defaultToneCount: Int,
    val assetCount: Int,
)

internal data class PendingRestore(
    val archiveContent: BackupArchiveContent,
    val plan: RestorePlan,
)

internal data class PlannedGroupToneRestore(
    val groupName: String,
    val toneId: String,
    val toneDisplayName: String,
)

internal data class RestorePlan(
    val snapshot: GrsBackupSnapshot,
    val groupTones: List<PlannedGroupToneRestore>,
) {
    val defaultToneCount: Int
        get() = snapshot.defaultTones.size

    val summary: RestoreSummary
        get() = RestoreSummary(
            groupsToApply = groupTones.size,
            defaultTonesToApply = defaultToneCount,
            audioFilesToRestore = snapshot.tones.size,
        )
}

internal data class RestoreSummary(
    val groupsToApply: Int,
    val defaultTonesToApply: Int,
    val audioFilesToRestore: Int,
)

internal data class RestorePreviewGroupTone(
    val groupName: String,
    val toneDisplayName: String,
)

internal data class RestorePreview(
    val summary: RestoreSummary,
    val groupTones: List<RestorePreviewGroupTone>,
)

internal data class RestoreExecutionResult(
    val groupsProcessed: Int,
    val contactsUpdated: Int,
    val defaultTonesApplied: Int,
    val missingGroups: Int,
    val failedTones: Int,
)
