package com.milen.grounpringtonesetter.backup

import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneType
import org.junit.Assert.assertEquals
import org.junit.Test

class RestorePlannerTest {

    @Test
    fun `planner maps saved groups to tone display names`() {
        val plan = RestorePlanner().plan(sampleSnapshot())

        assertEquals(2, plan.groupTones.size)
        assertEquals("Family", plan.groupTones[0].groupName)
        assertEquals("Family Bell.mp3", plan.groupTones[0].toneDisplayName)
        assertEquals("Work", plan.groupTones[1].groupName)
        assertEquals("Work Bell.mp3", plan.groupTones[1].toneDisplayName)
        assertEquals(2, plan.summary.groupsToApply)
        assertEquals(1, plan.summary.defaultTonesToApply)
        assertEquals(2, plan.summary.audioFilesToRestore)
    }

    @Test
    fun `planner skips group tones that reference missing assets`() {
        val snapshot = sampleSnapshot().copy(
            groupTones = listOf(
                GrsGroupToneSnapshot(groupName = "Family", toneId = "missing")
            )
        )

        val plan = RestorePlanner().plan(snapshot)

        assertEquals(0, plan.groupTones.size)
        assertEquals(0, plan.summary.groupsToApply)
        assertEquals(1, plan.summary.defaultTonesToApply)
        assertEquals(2, plan.summary.audioFilesToRestore)
    }

    private fun sampleSnapshot(): GrsBackupSnapshot =
        GrsBackupSnapshot(
            createdAtEpochMillis = 1L,
            appVersionName = "1.0",
            sourceLabel = "On device",
            groupTones = listOf(
                GrsGroupToneSnapshot(groupName = "Family", toneId = "family-tone"),
                GrsGroupToneSnapshot(groupName = "Work", toneId = "work-tone")
            ),
            defaultTones = listOf(
                GrsDefaultToneSnapshot(
                    type = DeviceDefaultToneType.RINGTONE,
                    toneId = "family-tone",
                    displayName = "Default Bell"
                )
            ),
            tones = listOf(
                GrsToneSnapshot(
                    id = "family-tone",
                    displayName = "Family Bell.mp3",
                    category = GrsToneCategory.RINGTONE,
                    archivePath = "assets/family.mp3",
                    sizeBytes = 10L
                ),
                GrsToneSnapshot(
                    id = "work-tone",
                    displayName = "Work Bell.mp3",
                    category = GrsToneCategory.RINGTONE,
                    archivePath = "assets/work.mp3",
                    sizeBytes = 20L
                )
            )
        )
}
