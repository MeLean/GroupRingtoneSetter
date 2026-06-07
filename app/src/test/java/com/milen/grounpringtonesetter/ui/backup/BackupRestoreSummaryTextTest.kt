package com.milen.grounpringtonesetter.ui.backup

import com.milen.grounpringtonesetter.backup.BackupExportResult
import com.milen.grounpringtonesetter.backup.RestoreExecutionResult
import com.milen.grounpringtonesetter.backup.RestorePreview
import com.milen.grounpringtonesetter.backup.RestorePreviewGroupTone
import com.milen.grounpringtonesetter.backup.RestoreSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupRestoreSummaryTextTest {

    @Test
    fun `buildExportSummary fills group default and asset counts`() {
        val result = BackupRestoreSummaryText.buildExportSummary(
            format = "Groups %1\$d Defaults %2\$d Assets %3\$d",
            result = BackupExportResult(
                groupToneCount = 2,
                defaultToneCount = 1,
                assetCount = 3
            )
        )

        assertEquals("Groups 2 Defaults 1 Assets 3", result)
    }

    @Test
    fun `buildRestorePreview appends group tone rows`() {
        val result = BackupRestoreSummaryText.buildRestorePreview(
            format = "Groups %1\$d Defaults %2\$d Assets %3\$d",
            groupToneLineFormat = "%1\$s -> %2\$s",
            preview = RestorePreview(
                summary = RestoreSummary(
                    groupsToApply = 2,
                    defaultTonesToApply = 1,
                    audioFilesToRestore = 3
                ),
                groupTones = listOf(
                    RestorePreviewGroupTone(
                        groupName = "Family",
                        toneDisplayName = "Bell.mp3"
                    ),
                    RestorePreviewGroupTone(
                        groupName = "Work",
                        toneDisplayName = "Desk.wav"
                    )
                )
            ),
            systemSettingsPermissionNote = "Permission note"
        )

        assertEquals(
            "Groups 2 Defaults 1 Assets 3\n\nFamily -> Bell.mp3\nWork -> Desk.wav\n\nPermission note",
            result
        )
    }

    @Test
    fun `buildRestorePreview skips system settings note without default tones`() {
        val result = BackupRestoreSummaryText.buildRestorePreview(
            format = "Groups %1\$d Defaults %2\$d Assets %3\$d",
            groupToneLineFormat = "%1\$s -> %2\$s",
            preview = RestorePreview(
                summary = RestoreSummary(
                    groupsToApply = 0,
                    defaultTonesToApply = 0,
                    audioFilesToRestore = 0
                ),
                groupTones = emptyList()
            ),
            systemSettingsPermissionNote = "Permission note"
        )

        assertEquals("Groups 0 Defaults 0 Assets 0", result)
    }

    @Test
    fun `buildRestoreResult fills restore execution counts`() {
        val result = BackupRestoreSummaryText.buildRestoreResult(
            format = "Groups %1\$d Contacts %2\$d Defaults %3\$d Missing %4\$d Failed %5\$d",
            result = RestoreExecutionResult(
                groupsProcessed = 1,
                contactsUpdated = 2,
                defaultTonesApplied = 3,
                missingGroups = 4,
                failedTones = 5
            )
        )

        assertEquals("Groups 1 Contacts 2 Defaults 3 Missing 4 Failed 5", result)
    }
}
