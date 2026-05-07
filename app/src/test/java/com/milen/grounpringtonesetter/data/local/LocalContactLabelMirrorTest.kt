package com.milen.grounpringtonesetter.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalContactLabelMirrorTest {

    @Test
    fun `parseMirrorAssignments prefers first marked relation row and keeps legacy fallback`() {
        val rows = listOf(
            relationRow(rowId = 1L, contactId = 10L, labelName = "Family"),
            relationRow(rowId = 2L, contactId = 10L, labelName = "Ignored duplicate"),
            legacyRow(rowId = 3L, contactId = 10L, labelName = "Legacy family", labelId = "family-id"),
            legacyRow(rowId = 4L, contactId = 20L, labelName = "Gym", labelId = "gym-id"),
            relationRow(rowId = 5L, contactId = 30L, labelName = "   ")
        )

        val parsed = parseMirrorAssignments(
            rows = rows,
            lookupKeysByContactId = mapOf(
                10L to "lookup-family",
                20L to "lookup-gym",
                30L to "lookup-blank"
            )
        )

        assertEquals(setOf(10L), parsed.duplicateRelationContactIds)
        assertEquals(2, parsed.assignments.size)
        assertEquals(
            MirroredLocalLabelAssignment(
                labelId = null,
                labelName = "Family",
                lookupKey = "lookup-family",
                contactId = 10L
            ),
            parsed.assignments[0]
        )
        assertEquals(
            MirroredLocalLabelAssignment(
                labelId = "gym-id",
                labelName = "Gym",
                lookupKey = "lookup-gym",
                contactId = 20L
            ),
            parsed.assignments[1]
        )
    }

    @Test
    fun `toLocalLabelMirrorRow maps marked relation provider row`() {
        val row = toLocalLabelMirrorRow(
            LocalLabelMirrorProviderRow(
                rowId = 1L,
                contactId = 10L,
                mimeType = android.provider.ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE,
                data1 = " Family ",
                data2 = android.provider.ContactsContract.CommonDataKinds.Relation.TYPE_CUSTOM.toString(),
                data3 = "com.milen.grounpringtonesetter.local_label"
            )
        )

        assertEquals(
            LocalLabelMirrorRow(
                rowId = 1L,
                contactId = 10L,
                kind = LocalLabelMirrorRowKind.RELATION_MARKER,
                labelName = "Family",
                labelId = null
            ),
            row
        )
    }

    @Test
    fun `buildMirrorSyncPlan backfills missing relation rows`() {
        val plan = buildMirrorSyncPlan(
            currentRows = emptyList(),
            expectedAssignments = listOf(
                ExpectedMirrorAssignment(contactId = 10L, labelName = "Family")
            ),
            resolveRawContactId = { 100L }
        )

        assertTrue(plan.deleteRowIds.isEmpty())
        assertTrue(plan.missingRawContactIds.isEmpty())
        assertTrue(plan.duplicateRelationContactIds.isEmpty())
        assertEquals(
            listOf(
                MirrorInsertAssignment(
                    contactId = 10L,
                    rawContactId = 100L,
                    labelName = "Family"
                )
            ),
            plan.insertAssignments
        )
    }

    @Test
    fun `buildMirrorSyncPlan updates renamed relation marker and migrates legacy rows`() {
        val plan = buildMirrorSyncPlan(
            currentRows = listOf(
                relationRow(rowId = 1L, contactId = 10L, labelName = "Old name"),
                legacyRow(rowId = 2L, contactId = 20L, labelName = "Gym", labelId = "gym-id")
            ),
            expectedAssignments = listOf(
                ExpectedMirrorAssignment(contactId = 10L, labelName = "New name"),
                ExpectedMirrorAssignment(contactId = 20L, labelName = "Gym")
            ),
            resolveRawContactId = { contactId -> if (contactId == 10L) 100L else 200L }
        )

        assertEquals(setOf(1L, 2L), plan.deleteRowIds)
        assertEquals(
            listOf(
                MirrorInsertAssignment(
                    contactId = 10L,
                    rawContactId = 100L,
                    labelName = "New name"
                ),
                MirrorInsertAssignment(
                    contactId = 20L,
                    rawContactId = 200L,
                    labelName = "Gym"
                )
            ),
            plan.insertAssignments
        )
        assertTrue(plan.missingRawContactIds.isEmpty())
    }

    @Test
    fun `buildMirrorSyncPlan removes stale rows and reports missing raw contact ids`() {
        val plan = buildMirrorSyncPlan(
            currentRows = listOf(
                relationRow(rowId = 1L, contactId = 10L, labelName = "Family"),
                legacyRow(rowId = 2L, contactId = 11L, labelName = "Work", labelId = "work-id")
            ),
            expectedAssignments = listOf(
                ExpectedMirrorAssignment(contactId = 10L, labelName = "Family"),
                ExpectedMirrorAssignment(contactId = 11L, labelName = "Work")
            ),
            resolveRawContactId = { contactId -> if (contactId == 10L) 100L else null }
        )

        assertEquals(setOf(2L), plan.deleteRowIds)
        assertEquals(setOf(11L), plan.missingRawContactIds)
        assertTrue(plan.insertAssignments.isEmpty())
    }

    @Test
    fun `buildMirrorSyncPlan removes stale rows for deleted local groups`() {
        val plan = buildMirrorSyncPlan(
            currentRows = listOf(
                relationRow(rowId = 1L, contactId = 10L, labelName = "Family"),
                legacyRow(rowId = 2L, contactId = 11L, labelName = "Work", labelId = "work-id")
            ),
            expectedAssignments = emptyList(),
            resolveRawContactId = { 100L }
        )

        assertEquals(setOf(1L, 2L), plan.deleteRowIds)
        assertTrue(plan.insertAssignments.isEmpty())
        assertTrue(plan.missingRawContactIds.isEmpty())
        assertTrue(plan.duplicateRelationContactIds.isEmpty())
    }

    @Test
    fun `buildMirrorSyncPlan keeps first duplicate relation row and deletes the rest`() {
        val plan = buildMirrorSyncPlan(
            currentRows = listOf(
                relationRow(rowId = 1L, contactId = 10L, labelName = "Family"),
                relationRow(rowId = 2L, contactId = 10L, labelName = "Family")
            ),
            expectedAssignments = listOf(
                ExpectedMirrorAssignment(contactId = 10L, labelName = "Family")
            ),
            resolveRawContactId = { 100L }
        )

        assertEquals(setOf(10L), plan.duplicateRelationContactIds)
        assertEquals(setOf(2L), plan.deleteRowIds)
        assertTrue(plan.insertAssignments.isEmpty())
        assertTrue(plan.missingRawContactIds.isEmpty())
    }

    private fun relationRow(
        rowId: Long,
        contactId: Long,
        labelName: String,
    ) = LocalLabelMirrorRow(
        rowId = rowId,
        contactId = contactId,
        kind = LocalLabelMirrorRowKind.RELATION_MARKER,
        labelName = labelName,
        labelId = null
    )

    private fun legacyRow(
        rowId: Long,
        contactId: Long,
        labelName: String,
        labelId: String,
    ) = LocalLabelMirrorRow(
        rowId = rowId,
        contactId = contactId,
        kind = LocalLabelMirrorRowKind.LEGACY_CUSTOM,
        labelName = labelName,
        labelId = labelId
    )
}
