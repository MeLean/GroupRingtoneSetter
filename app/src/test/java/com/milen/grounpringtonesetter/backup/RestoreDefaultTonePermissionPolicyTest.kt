package com.milen.grounpringtonesetter.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreDefaultTonePermissionPolicyTest {

    @Test
    fun `requests write settings only when default tones are present and permission is missing`() {
        assertTrue(
            RestoreDefaultTonePermissionPolicy.shouldRequestWriteSettings(
                defaultToneCount = 2,
                canWriteSystemSettings = false
            )
        )
        assertFalse(
            RestoreDefaultTonePermissionPolicy.shouldRequestWriteSettings(
                defaultToneCount = 2,
                canWriteSystemSettings = true
            )
        )
        assertFalse(
            RestoreDefaultTonePermissionPolicy.shouldRequestWriteSettings(
                defaultToneCount = 0,
                canWriteSystemSettings = false
            )
        )
    }

    @Test
    fun `missing write settings skips only default tone operations`() {
        val skippedCount = RestoreDefaultTonePermissionPolicy.skippedDefaultToneCount(
            defaultToneCount = 3,
            canWriteSystemSettings = false
        )

        assertEquals(3, skippedCount)
    }

    @Test
    fun `granted write settings skips no default tone operations`() {
        val skippedCount = RestoreDefaultTonePermissionPolicy.skippedDefaultToneCount(
            defaultToneCount = 3,
            canWriteSystemSettings = true
        )

        assertEquals(0, skippedCount)
    }
}
