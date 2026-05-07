package com.milen.grounpringtonesetter.data.local

import android.app.Application
import android.content.ContentValues
import android.provider.ContactsContract
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import com.milen.grounpringtonesetter.utils.Tracker
import kotlinx.coroutines.withContext

private const val LEGACY_MIME_TYPE =
    "vnd.android.cursor.item/vnd.com.milen.grounpringtonesetter.local_label"
private const val RELATION_MARKER_LABEL =
    "com.milen.grounpringtonesetter.local_label"

internal class LocalContactLabelMirror(
    private val appContext: Application,
    private val tracker: Tracker,
) {
    companion object {
        private val MIRROR_SELECTION = buildString {
            append('(')
            append(ContactsContract.Data.MIMETYPE)
            append(" = ?)")
            append(" OR (")
            append(ContactsContract.Data.MIMETYPE)
            append(" = ? AND ")
            append(ContactsContract.CommonDataKinds.Relation.TYPE)
            append(" = ? AND ")
            append(ContactsContract.CommonDataKinds.Relation.LABEL)
            append(" = ?)")
        }

        private val MIRROR_SELECTION_ARGS = arrayOf(
            LEGACY_MIME_TYPE,
            ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Relation.TYPE_CUSTOM.toString(),
            RELATION_MARKER_LABEL
        )
    }

    suspend fun readAssignments(): List<MirroredLocalLabelAssignment> =
        withContext(DispatchersProvider.io) {
            val rows = queryMirrorRows()
            if (rows.isEmpty()) return@withContext emptyList()

            val lookupKeys = resolveLookupKeys(rows.map { it.contactId }.distinct())
            val parsed = parseMirrorAssignments(rows, lookupKeys)
            parsed.duplicateRelationContactIds.forEach(::trackDuplicateRelationRows)
            trackReadTelemetry(parsed)
            parsed.assignments
        }

    suspend fun syncAssignments(
        expectedAssignments: List<ExpectedMirrorAssignment>,
        resolveRawContactId: (Long) -> Long?,
        onMissingRawContact: (Long) -> Unit,
    ) = withContext(DispatchersProvider.io) {
        val plan = buildMirrorSyncPlan(
            currentRows = queryMirrorRows(),
            expectedAssignments = expectedAssignments,
            resolveRawContactId = resolveRawContactId
        )

        plan.duplicateRelationContactIds.forEach(::trackDuplicateRelationRows)
        plan.deleteRowIds.forEach(::deleteRowById)
        plan.insertAssignments.forEach { assignment ->
            insertRelationAssignment(
                rawContactId = assignment.rawContactId,
                labelName = assignment.labelName
            )
        }
        plan.missingRawContactIds.forEach(onMissingRawContact)
        trackSyncTelemetry(plan, expectedAssignments.size)
    }

    private fun insertRelationAssignment(
        rawContactId: Long,
        labelName: String,
    ) {
        val values = ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            put(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE
            )
            put(ContactsContract.CommonDataKinds.Relation.TYPE, ContactsContract.CommonDataKinds.Relation.TYPE_CUSTOM)
            put(ContactsContract.CommonDataKinds.Relation.LABEL, RELATION_MARKER_LABEL)
            put(ContactsContract.CommonDataKinds.Relation.NAME, labelName)
        }
        val inserted = appContext.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values)
        if (inserted == null) {
            tracker.trackError(
                IllegalStateException(
                    "Failed to insert local label relation marker for rawContactId=$rawContactId"
                )
            )
        }
    }

    private fun deleteRowById(rowId: Long) {
        val deleted = appContext.contentResolver.delete(
            ContactsContract.Data.CONTENT_URI,
            "${ContactsContract.Data._ID} = ?",
            arrayOf(rowId.toString())
        )
        if (deleted <= 0) {
            tracker.trackEvent(
                "local_label_mirror_delete_miss",
                mapOf("row_id" to rowId.toString())
            )
        }
    }

    private fun queryMirrorRows(): List<LocalLabelMirrorRow> {
        val providerRows = mutableListOf<LocalLabelMirrorProviderRow>()
        appContext.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3
            ),
            MIRROR_SELECTION,
            MIRROR_SELECTION_ARGS,
            "${ContactsContract.Data._ID} ASC"
        )?.use { cursor ->
            val rowIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data._ID)
            val contactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val mimeTypeIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            val data1Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
            val data2Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA2)
            val data3Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA3)
            while (cursor.moveToNext()) {
                providerRows += LocalLabelMirrorProviderRow(
                    rowId = cursor.getLong(rowIdIndex),
                    contactId = cursor.getLong(contactIdIndex),
                    mimeType = cursor.getString(mimeTypeIndex).orEmpty(),
                    data1 = cursor.getString(data1Index),
                    data2 = cursor.getString(data2Index),
                    data3 = cursor.getString(data3Index)
                )
            }
        }

        return providerRows.mapNotNull(::toLocalLabelMirrorRow)
    }

    private fun trackDuplicateRelationRows(contactId: Long) {
        tracker.trackError(
            IllegalStateException(
                "Duplicate local label relation markers for contactId=$contactId"
            )
        )
    }

    private fun resolveLookupKeys(contactIds: List<Long>): Map<Long, String> {
        if (contactIds.isEmpty()) return emptyMap()
        val result = linkedMapOf<Long, String>()
        contactIds.chunked(200).forEach { chunk ->
            val selection = "${ContactsContract.Contacts._ID} IN (${chunk.joinToString(",")})"
            appContext.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.LOOKUP_KEY
                ),
                selection,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val lookupKeyIndex =
                    cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(idIndex)
                    val lookupKey = cursor.getString(lookupKeyIndex).orEmpty()
                    if (lookupKey.isNotBlank()) {
                        result[contactId] = lookupKey
                    }
                }
            }
        }
        return result
    }

    private fun trackReadTelemetry(parsed: ParsedMirrorAssignments) {
        if (
            parsed.legacyContactIdsUsed.isEmpty() &&
            parsed.missingLookupContactIds.isEmpty() &&
            parsed.blankLabelContactIds.isEmpty() &&
            parsed.duplicateRelationContactIds.isEmpty()
        ) {
            return
        }

        tracker.trackEvent(
            "local_label_mirror_read_edge",
            mapOf(
                "assignment_count" to parsed.assignments.size,
                "relation_row_count" to parsed.relationRowCount,
                "legacy_row_count" to parsed.legacyRowCount,
                "legacy_contact_count" to parsed.legacyContactIdsUsed.size,
                "missing_lookup_count" to parsed.missingLookupContactIds.size,
                "blank_label_count" to parsed.blankLabelContactIds.size,
                "duplicate_relation_count" to parsed.duplicateRelationContactIds.size
            )
        )
    }

    private fun trackSyncTelemetry(
        plan: MirrorSyncPlan,
        expectedAssignmentCount: Int,
    ) {
        if (
            plan.deleteRowIds.isEmpty() &&
            plan.insertAssignments.isEmpty() &&
            plan.missingRawContactIds.isEmpty() &&
            plan.duplicateRelationContactIds.isEmpty()
        ) {
            return
        }

        tracker.trackEvent(
            "local_label_mirror_sync_plan",
            mapOf(
                "expected_count" to expectedAssignmentCount,
                "delete_count" to plan.deleteRowIds.size,
                "insert_count" to plan.insertAssignments.size,
                "missing_raw_count" to plan.missingRawContactIds.size,
                "duplicate_relation_count" to plan.duplicateRelationContactIds.size
            )
        )
    }

}

internal data class ExpectedMirrorAssignment(
    val contactId: Long,
    val labelName: String,
)

internal data class LocalLabelMirrorProviderRow(
    val rowId: Long,
    val contactId: Long,
    val mimeType: String,
    val data1: String?,
    val data2: String?,
    val data3: String?,
)

internal enum class LocalLabelMirrorRowKind {
    RELATION_MARKER,
    LEGACY_CUSTOM,
}

internal data class LocalLabelMirrorRow(
    val rowId: Long,
    val contactId: Long,
    val kind: LocalLabelMirrorRowKind,
    val labelName: String,
    val labelId: String?,
)

internal data class ParsedMirrorAssignments(
    val assignments: List<MirroredLocalLabelAssignment>,
    val duplicateRelationContactIds: Set<Long>,
    val legacyContactIdsUsed: Set<Long>,
    val missingLookupContactIds: Set<Long>,
    val blankLabelContactIds: Set<Long>,
    val relationRowCount: Int,
    val legacyRowCount: Int,
)

internal data class MirrorInsertAssignment(
    val contactId: Long,
    val rawContactId: Long,
    val labelName: String,
)

internal data class MirrorSyncPlan(
    val deleteRowIds: Set<Long>,
    val insertAssignments: List<MirrorInsertAssignment>,
    val missingRawContactIds: Set<Long>,
    val duplicateRelationContactIds: Set<Long>,
)

internal fun parseMirrorAssignments(
    rows: List<LocalLabelMirrorRow>,
    lookupKeysByContactId: Map<Long, String>,
): ParsedMirrorAssignments {
    val duplicateRelationContactIds = linkedSetOf<Long>()
    val legacyContactIdsUsed = linkedSetOf<Long>()
    val missingLookupContactIds = linkedSetOf<Long>()
    val blankLabelContactIds = linkedSetOf<Long>()
    val assignments = rows
        .groupBy { it.contactId }
        .values
        .mapNotNull { contactRows ->
            val relationRows = contactRows.filter { it.kind == LocalLabelMirrorRowKind.RELATION_MARKER }
            if (relationRows.size > 1) {
                duplicateRelationContactIds += contactRows.first().contactId
            }
            val canonicalRow = relationRows.firstOrNull { it.labelName.isNotBlank() }
                ?: contactRows.firstOrNull {
                    it.kind == LocalLabelMirrorRowKind.LEGACY_CUSTOM && it.labelName.isNotBlank()
                }
                ?: relationRows.firstOrNull()
                ?: contactRows.firstOrNull { it.kind == LocalLabelMirrorRowKind.LEGACY_CUSTOM }
                ?: return@mapNotNull null

            val lookupKey = lookupKeysByContactId[canonicalRow.contactId].orEmpty()
            val normalizedLabelName = canonicalRow.labelName.trim()
            if (lookupKey.isBlank()) {
                missingLookupContactIds += canonicalRow.contactId
                null
            } else if (normalizedLabelName.isBlank()) {
                blankLabelContactIds += canonicalRow.contactId
                null
            } else {
                if (canonicalRow.kind == LocalLabelMirrorRowKind.LEGACY_CUSTOM) {
                    legacyContactIdsUsed += canonicalRow.contactId
                }
                MirroredLocalLabelAssignment(
                    labelId = canonicalRow.labelId?.takeIf { it.isNotBlank() },
                    labelName = normalizedLabelName,
                    lookupKey = lookupKey,
                    contactId = canonicalRow.contactId
                )
            }
        }

    return ParsedMirrorAssignments(
        assignments = assignments,
        duplicateRelationContactIds = duplicateRelationContactIds,
        legacyContactIdsUsed = legacyContactIdsUsed,
        missingLookupContactIds = missingLookupContactIds,
        blankLabelContactIds = blankLabelContactIds,
        relationRowCount = rows.count { it.kind == LocalLabelMirrorRowKind.RELATION_MARKER },
        legacyRowCount = rows.count { it.kind == LocalLabelMirrorRowKind.LEGACY_CUSTOM }
    )
}

internal fun buildMirrorSyncPlan(
    currentRows: List<LocalLabelMirrorRow>,
    expectedAssignments: List<ExpectedMirrorAssignment>,
    resolveRawContactId: (Long) -> Long?,
): MirrorSyncPlan {
    val expectedByContactId = linkedMapOf<Long, ExpectedMirrorAssignment>()
    expectedAssignments.forEach { assignment ->
        expectedByContactId.putIfAbsent(assignment.contactId, assignment)
    }

    val deleteRowIds = linkedSetOf<Long>()
    val insertAssignments = mutableListOf<MirrorInsertAssignment>()
    val missingRawContactIds = linkedSetOf<Long>()
    val duplicateRelationContactIds = linkedSetOf<Long>()
    val currentRowsByContactId = currentRows.groupBy { it.contactId }

    currentRowsByContactId.forEach { (contactId, contactRows) ->
        val expected = expectedByContactId[contactId]
        val relationRows = contactRows
            .filter { it.kind == LocalLabelMirrorRowKind.RELATION_MARKER }
            .sortedBy { it.rowId }
        val legacyRows = contactRows.filter { it.kind == LocalLabelMirrorRowKind.LEGACY_CUSTOM }

        if (relationRows.size > 1) {
            duplicateRelationContactIds += contactId
            relationRows.drop(1).forEach { row -> deleteRowIds += row.rowId }
        }

        val primaryRelation = relationRows.firstOrNull()
        if (expected == null) {
            contactRows.forEach { row -> deleteRowIds += row.rowId }
            return@forEach
        }

        legacyRows.forEach { row -> deleteRowIds += row.rowId }
        if (primaryRelation?.labelName == expected.labelName) {
            return@forEach
        }

        primaryRelation?.let { row -> deleteRowIds += row.rowId }
        val rawContactId = resolveRawContactId(contactId)
        if (rawContactId == null) {
            missingRawContactIds += contactId
            return@forEach
        }

        insertAssignments += MirrorInsertAssignment(
            contactId = contactId,
            rawContactId = rawContactId,
            labelName = expected.labelName
        )
    }

    expectedByContactId.forEach { (contactId, expected) ->
        if (contactId in currentRowsByContactId) return@forEach

        val rawContactId = resolveRawContactId(contactId)
        if (rawContactId == null) {
            missingRawContactIds += contactId
            return@forEach
        }

        insertAssignments += MirrorInsertAssignment(
            contactId = contactId,
            rawContactId = rawContactId,
            labelName = expected.labelName
        )
    }

    return MirrorSyncPlan(
        deleteRowIds = deleteRowIds,
        insertAssignments = insertAssignments,
        missingRawContactIds = missingRawContactIds,
        duplicateRelationContactIds = duplicateRelationContactIds
    )
}

internal fun toLocalLabelMirrorRow(row: LocalLabelMirrorProviderRow): LocalLabelMirrorRow? {
    return when {
        row.mimeType == ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE &&
                row.data2 == ContactsContract.CommonDataKinds.Relation.TYPE_CUSTOM.toString() &&
                row.data3 == RELATION_MARKER_LABEL -> {
            LocalLabelMirrorRow(
                rowId = row.rowId,
                contactId = row.contactId,
                kind = LocalLabelMirrorRowKind.RELATION_MARKER,
                labelName = row.data1.orEmpty().trim(),
                labelId = null
            )
        }

        row.mimeType == LEGACY_MIME_TYPE -> {
            LocalLabelMirrorRow(
                rowId = row.rowId,
                contactId = row.contactId,
                kind = LocalLabelMirrorRowKind.LEGACY_CUSTOM,
                labelName = row.data1.orEmpty().trim(),
                labelId = row.data2?.takeIf { it.isNotBlank() }
            )
        }

        else -> null
    }
}
