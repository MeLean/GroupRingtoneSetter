package com.milen.grounpringtonesetter.data.local

import android.app.Application
import android.content.ContentValues
import android.provider.ContactsContract
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import kotlinx.coroutines.withContext

internal class LocalContactLabelMirror(
    private val appContext: Application,
) {
    companion object {
        const val MIME_TYPE =
            "vnd.android.cursor.item/vnd.com.milen.grounpringtonesetter.local_label"
    }

    suspend fun readAssignments(): List<MirroredLocalLabelAssignment> =
        withContext(DispatchersProvider.io) {
            val rows = mutableListOf<MirrorRow>()
            appContext.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.Data.DATA1,
                    ContactsContract.Data.DATA2
                ),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(MIME_TYPE),
                null
            )?.use { cursor ->
                val contactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
                val data1Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
                val data2Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA2)
                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(contactIdIndex)
                    val labelName = cursor.getString(data1Index).orEmpty()
                    val labelId = cursor.getString(data2Index)
                    rows += MirrorRow(
                        contactId = contactId,
                        labelName = labelName,
                        labelId = labelId
                    )
                }
            }

            if (rows.isEmpty()) return@withContext emptyList()

            val lookupKeys = resolveLookupKeys(rows.map { it.contactId }.distinct())
            rows.mapNotNull { row ->
                val lookupKey = lookupKeys[row.contactId].orEmpty()
                if (lookupKey.isBlank() || row.labelName.isBlank()) {
                    null
                } else {
                    MirroredLocalLabelAssignment(
                        labelId = row.labelId?.takeIf { it.isNotBlank() },
                        labelName = row.labelName,
                        lookupKey = lookupKey,
                        contactId = row.contactId
                    )
                }
            }
        }

    suspend fun upsertAssignment(
        contactId: Long,
        rawContactId: Long,
        labelId: String,
        labelName: String,
    ) = withContext(DispatchersProvider.io) {
        clearAssignment(contactId)
        val values = ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            put(ContactsContract.Data.MIMETYPE, MIME_TYPE)
            put(ContactsContract.Data.DATA1, labelName)
            put(ContactsContract.Data.DATA2, labelId)
        }
        appContext.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values)
    }

    suspend fun clearAssignment(contactId: Long) = withContext(DispatchersProvider.io) {
        appContext.contentResolver.delete(
            ContactsContract.Data.CONTENT_URI,
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.CONTACT_ID} = ?",
            arrayOf(MIME_TYPE, contactId.toString())
        )
    }

    suspend fun updateLabelName(
        labelId: String,
        labelName: String,
    ) = withContext(DispatchersProvider.io) {
        val values = ContentValues().apply {
            put(ContactsContract.Data.DATA1, labelName)
        }
        appContext.contentResolver.update(
            ContactsContract.Data.CONTENT_URI,
            values,
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DATA2} = ?",
            arrayOf(MIME_TYPE, labelId)
        )
    }

    suspend fun deleteAssignmentsForLabel(labelId: String) = withContext(DispatchersProvider.io) {
        appContext.contentResolver.delete(
            ContactsContract.Data.CONTENT_URI,
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DATA2} = ?",
            arrayOf(MIME_TYPE, labelId)
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
}

private data class MirrorRow(
    val contactId: Long,
    val labelName: String,
    val labelId: String?,
)
