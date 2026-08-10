package com.milen.grounpringtonesetter.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupDeletionCapabilityTest {
    @Test
    fun `canDeleteGroup returns false for read-only groups`() {
        val capability = GroupDeletionCapability(
            isDeleted = false,
            isReadOnly = true,
            systemId = null
        )

        assertFalse(canDeleteGroup(capability))
        assertFalse(canModifyGroup(capability))
    }

    @Test
    fun `canDeleteGroup returns false for system groups`() {
        val capability = GroupDeletionCapability(
            isDeleted = false,
            isReadOnly = false,
            systemId = "Contacts"
        )

        assertFalse(canDeleteGroup(capability))
        assertFalse(canModifyGroup(capability))
    }

    @Test
    fun `canDeleteGroup returns true for user editable groups`() {
        val capability = GroupDeletionCapability(
            isDeleted = false,
            isReadOnly = false,
            systemId = null
        )

        assertTrue(canDeleteGroup(capability))
        assertTrue(canModifyGroup(capability))
    }
}
