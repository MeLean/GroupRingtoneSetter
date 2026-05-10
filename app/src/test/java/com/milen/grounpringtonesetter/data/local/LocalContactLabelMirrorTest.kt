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
    fun `buildMirrorPurgePlan keeps purge a no-op when there are no rows`() {
        val plan = buildMirrorPurgePlan(currentRows = emptyList())

        assertTrue(plan.deleteRowIds.isEmpty())
        assertEquals(0, plan.relationRowCount)
        assertEquals(0, plan.legacyRowCount)
    }

    @Test
    fun `buildMirrorPurgePlan deletes all recognized rows including malformed ones`() {
        val plan = buildMirrorPurgePlan(
            currentRows = listOf(
                relationRow(rowId = 1L, contactId = 10L, labelName = "Family"),
                legacyRow(rowId = 2L, contactId = 10L, labelName = "Legacy Family", labelId = "family-id"),
                relationRow(rowId = 3L, contactId = 11L, labelName = "   ")
            )
        )

        assertEquals(linkedSetOf(1L, 2L, 3L), plan.deleteRowIds)
        assertEquals(2, plan.relationRowCount)
        assertEquals(1, plan.legacyRowCount)
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
