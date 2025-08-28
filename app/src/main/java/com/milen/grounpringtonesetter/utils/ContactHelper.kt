package com.milen.grounpringtonesetter.utils

import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.ContactsContract
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.accounts.AccountId
import com.milen.grounpringtonesetter.data.exceptions.NoContactsFoundException
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import kotlinx.coroutines.withContext

internal class ContactsHelper(
    private val appContext: Application,
    private val preferenceHelper: EncryptedPreferencesHelper,
    private val contactRingtoneUpdateHelper: ContactRingtoneUpdateHelper,
    private val tracker: Tracker,
) {

    private fun getContactIdsForLabel(labelId: Long): List<Long> {
        val ids = LinkedHashSet<Long>() // dedupe if multiple raw contacts map to same CONTACT_ID
        val uri = ContactsContract.Data.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTACT_ID)
        val selection =
            "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID}=? AND " +
                    "${ContactsContract.Data.MIMETYPE}=?"
        val args = arrayOf(
            labelId.toString(),
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
        )
        appContext.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
            val idxId =
                c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.GroupMembership.CONTACT_ID)
            while (c.moveToNext()) ids.add(c.getLong(idxId))
        }
        return ids.toList()
    }

    suspend fun getAllLabelItemsShallow(
        includeDeviceContacts: Boolean = true,
    ): List<LabelItem> = withContext(DispatchersProvider.io) {
        tracker.trackEvent("getAllLabels SHALLOW called")
        val out = ArrayList<LabelItem>()
        val uri = ContactsContract.Groups.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.Groups._ID,
            ContactsContract.Groups.TITLE,
            ContactsContract.Groups.ACCOUNT_TYPE,
            ContactsContract.Groups.ACCOUNT_NAME,
            ContactsContract.Groups.GROUP_IS_READ_ONLY,
            ContactsContract.Groups.DELETED
        )
        val selection = if (includeDeviceContacts) {
            "${ContactsContract.Groups.DELETED}=0 AND ${ContactsContract.Groups.GROUP_IS_READ_ONLY}=0"
        } else {
            "${ContactsContract.Groups.DELETED}=0 AND ${ContactsContract.Groups.GROUP_IS_READ_ONLY}=0 " +
                    "AND ${ContactsContract.Groups.ACCOUNT_TYPE}=?"
        }
        val selectionArgs = if (includeDeviceContacts) null else arrayOf("com.google")

        appContext.contentResolver.query(uri, projection, selection, selectionArgs, null)
            ?.use { c ->
                val idxId = c.getColumnIndexOrThrow(ContactsContract.Groups._ID)
                val idxTitle = c.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)
                while (c.moveToNext()) {
                    val gid = c.getLong(idxId)
                    val gname = c.getString(idxTitle) ?: ""
                    val ids = getContactIdsForLabel(gid)
                    val contacts = ids.map { id ->
                        Contact(id = id, name = "", phone = null, ringtoneUriStr = null)
                    }
                    out.add(
                        LabelItem(
                            id = gid,
                            groupName = gname,
                            contacts = contacts,
                            ringtoneUriList = emptyList(),
                            ringtoneFileName = ""
                        )
                    )
                }
            }
        out
    }

    suspend fun getAllLabelItemsForAccountsShallow(
        selectedAccounts: AccountId,
    ): List<LabelItem> = withContext(DispatchersProvider.io) {
        val allowed = mutableSetOf<Long>()
        val where =
            "${ContactsContract.Groups.DELETED}=0 AND " +
                    "${ContactsContract.Groups.ACCOUNT_TYPE}=? AND " +
                    "${ContactsContract.Groups.ACCOUNT_NAME}=?"
        val args = arrayOf(selectedAccounts.type, selectedAccounts.name)
        appContext.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups._ID),
            where,
            args,
            null
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(ContactsContract.Groups._ID)
            while (c.moveToNext()) allowed.add(c.getLong(idx))
        }
        if (allowed.isEmpty()) return@withContext emptyList()

        val allShallow = getAllLabelItemsShallow(includeDeviceContacts = true)
        allShallow.filter { it.id in allowed }
    }

    suspend fun getRingtonesForContactsBatched(
        contactIds: List<Long>,
        batchSize: Int = 100,
    ): Map<Long, String?> = withContext(DispatchersProvider.io) {
        if (contactIds.isEmpty()) return@withContext emptyMap<Long, String?>()
        val resolver = appContext.contentResolver
        val result = HashMap<Long, String?>()

        contactIds.chunked(batchSize).forEach { chunk ->
            if (chunk.isEmpty()) return@forEach
            val selection = "${ContactsContract.Contacts._ID} IN (${chunk.joinToString(",")})"
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.CUSTOM_RINGTONE
            )
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                selection,
                null,
                null
            )?.use { c ->
                val idxId = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val idxTone = c.getColumnIndexOrThrow(ContactsContract.Contacts.CUSTOM_RINGTONE)
                while (c.moveToNext()) {
                    val id = c.getLong(idxId)
                    val tone = if (!c.isNull(idxTone)) c.getString(idxTone) else null
                    result[id] = tone
                }
            }
        }
        result
    }

    suspend fun getAllPhoneContacts(accountId: AccountId?): List<Contact> =
        withContext(DispatchersProvider.io) {
            tracker.trackEvent(
                "getAllPhoneContacts called",
                mapOf("account" to (accountId?.label ?: "ALL"))
            )

            val cr = appContext.contentResolver
            val contactsProjection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.CUSTOM_RINGTONE
            )

            fun queryContacts(selection: String?, args: Array<String>?): MutableList<Contact> {
                val out = mutableListOf<Contact>()
                cr.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    contactsProjection,
                    selection,
                    args,
                    null
                )
                    ?.use { cursor ->
                        val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                        val nameIdx =
                            cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                        val toneIdx =
                            cursor.getColumnIndexOrThrow(ContactsContract.Contacts.CUSTOM_RINGTONE)

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idIdx)
                            val name = cursor.getString(nameIdx).orEmpty()
                            val phone = appContext.getPrimaryPhoneNumberForContact(id)
                            val ringtoneUriStr = cursor.getString(toneIdx)
                            out.add(
                                Contact(
                                    id = id,
                                    name = name,
                                    phone = phone,
                                    ringtoneUriStr = ringtoneUriStr
                                )
                            )
                        }
                    } ?: throw NoContactsFoundException()
                return out
            }

            // If no account was provided: track non-fatal and return ALL contacts (your original behavior)
            if (accountId == null) {
                tracker.trackEvent(
                    "getAllPhoneContacts accountId null",
                    mapOf("non_fatal" to "true")
                )
                return@withContext queryContacts(null, null).also {
                    tracker.trackEvent(
                        "getAllPhoneContacts loaded",
                        mapOf("count" to it.size.toString(), "account" to "ALL")
                    )
                }
            }

            // 1) Find contact IDs that belong to the given account (RawContacts -> CONTACT_ID)
            val rawSel = "${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
                    "${ContactsContract.RawContacts.DELETED}=0"
            val rawArgs = arrayOf(accountId.name, accountId.type)

            val contactIds = LinkedHashSet<Long>() // stable order
            cr.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                rawSel,
                rawArgs,
                null
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)
                while (c.moveToNext()) contactIds.add(c.getLong(idx))
            }

            if (contactIds.isEmpty()) {
                tracker.trackEvent(
                    "getAllPhoneContacts loaded",
                    mapOf("count" to "0", "account" to accountId.label)
                )
                return@withContext emptyList()
            }

            // 2) Query Contacts for those IDs (batched to avoid SQLite arg limits ~999)
            val results = mutableListOf<Contact>()
            val idsList = contactIds.toList()
            val batchSize = 900
            var start = 0
            while (start < idsList.size) {
                val end = minOf(start + batchSize, idsList.size)
                val batch = idsList.subList(start, end)
                val placeholders = batch.joinToString(",") { "?" }
                val sel = "${ContactsContract.Contacts._ID} IN ($placeholders)"
                val args = batch.map { it.toString() }.toTypedArray()
                results += queryContacts(sel, args)
                start = end
            }

            return@withContext results.also {
                tracker.trackEvent(
                    "getAllPhoneContacts loaded",
                    mapOf("count" to it.size.toString(), "account" to accountId.label)
                )
            }
        }

    fun updateLabelName(labelId: Long, newLabelName: String) {
        tracker.trackEvent(
            "updateLabelName called",
            mapOf("labelId" to labelId.toString(), "newLabelName" to newLabelName)
        )

        val labelNameValues = ContentValues().apply {
            put(ContactsContract.Groups.TITLE, newLabelName)
        }

        val selection = "${ContactsContract.Groups._ID} = ?"
        val selectionArgs = arrayOf(labelId.toString())

        val rowsUpdated = appContext.contentResolver.update(
            ContactsContract.Groups.CONTENT_URI,
            labelNameValues,
            selection,
            selectionArgs
        )

        if (rowsUpdated <= 0) {
            val errorMessage = "Failed to update label name for labelId: $labelId"
            tracker.trackError(IllegalStateException(errorMessage))
            throw IllegalStateException(errorMessage)
        }

        triggerSyncForAllAccounts()
        tracker.trackEvent("updateLabelName successful", mapOf("labelId" to labelId.toString()))
    }

    fun deleteLabel(labelId: Long) {
        tracker.trackEvent("deleteLabel called", mapOf("labelId" to labelId.toString()))

        // Remove label associations from all contacts
        removeAllContactsFromLabel(labelId)

        // Delete the label itself
        val labelUri = ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, labelId)

        val rowsDeleted = appContext.contentResolver.delete(labelUri, null, null)

        if (rowsDeleted <= 0) {
            val errorMessage = "Failed to delete label with ID: $labelId"
            tracker.trackError(IllegalStateException(errorMessage))
            throw IllegalStateException(errorMessage)
        }

        triggerSyncForAllAccounts()

        tracker.trackEvent("deleteLabel successful", mapOf("labelId" to labelId.toString()))
    }

    fun addAllContactsToLabel(labelId: Long, includedContacts: List<Contact>): Unit =
        includedContacts.forEach {
            addSingleContactToLabel(labelId = labelId, contactId = it.id)
        }.also {
            triggerSyncForAllAccounts()
            tracker.trackEvent("addAllContactsToGroup called")
        }

    fun removeAllContactsFromLabel(labelId: Long, excludedContacts: List<Contact>) {
        tracker.trackEvent(
            "removeAllContactsFromLabel called",
            mapOf(
                "labelId" to labelId.toString(),
                "contactCount" to excludedContacts.size.toString()
            )
        )
        val ops = ArrayList<ContentProviderOperation>()

        excludedContacts.forEach { contact ->
            val selection =
                "${ContactsContract.CommonDataKinds.GroupMembership.CONTACT_ID} = ? AND " +
                        "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ? AND " +
                        "${ContactsContract.CommonDataKinds.GroupMembership.MIMETYPE} = ?"
            val selectionArgs = arrayOf(
                contact.id.toString(),
                labelId.toString(),
                ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
            )

            ops.add(
                ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection(selection, selectionArgs)
                    .build()
            )
        }

        // Apply the batch operation to remove the contacts from the label
        val result = appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)

        triggerSyncForAllAccounts()

        tracker.trackEvent(
            "removeAllContactsFromLabel successful",
            mapOf("labelId" to labelId.toString(), "removedCount" to result.size.toString())
        )
    }

    suspend fun getAllLabelItems(includeDeviceContacts: Boolean = true): List<LabelItem> =
        withContext(DispatchersProvider.io) {
            tracker.trackEvent("getAllLabels called")
            val labels = mutableListOf<LabelItem>()
            val uri = ContactsContract.Groups.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.Groups._ID,
                ContactsContract.Groups.TITLE,
                ContactsContract.Groups.ACCOUNT_TYPE,
                ContactsContract.Groups.ACCOUNT_NAME,
                ContactsContract.Groups.GROUP_IS_READ_ONLY,
                ContactsContract.Groups.DELETED
            )

            // Build selection query based on user preference
            val selection = if (includeDeviceContacts) {
                "${ContactsContract.Groups.DELETED} = 0 AND ${ContactsContract.Groups.GROUP_IS_READ_ONLY} = 0"
            } else {
                "${ContactsContract.Groups.DELETED} = 0 AND ${ContactsContract.Groups.GROUP_IS_READ_ONLY} = 0 AND ${ContactsContract.Groups.ACCOUNT_TYPE} = ?"
            }

            val selectionArgs = if (includeDeviceContacts) null else arrayOf("com.google")

            // Query the ContentResolver
            appContext.contentResolver.query(uri, projection, selection, selectionArgs, null)
                ?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups._ID)
                    val titleIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIndex)
                        val title = cursor.getString(titleIndex).orEmpty()
                        if (title.isBlank()) continue

                        // Fetch contacts for the label
                        val contacts = getContactsForLabel(id)

                        // Extract ringtone URIs from the contacts
                        val ringtoneUris = contacts.mapNotNull { it.ringtoneUriStr }.distinct()

                        // Stringify ringtone URIs and save to preferences
                        val ringtoneFileName = ringtoneUris.stringifyContacts(preferenceHelper)

                        // Add the label to the list
                        labels.add(
                            LabelItem(
                                id = id,
                                groupName = title,
                                contacts = contacts,
                                ringtoneUriList = ringtoneUris,
                                ringtoneFileName = ringtoneFileName
                            )
                        )
                    }
                } ?: tracker.trackEvent("Query returned null cursor for Labels")

            return@withContext labels
        }

    suspend fun setRingtoneToLabelContacts(
        labelContacts: List<Contact>,
        newRingtoneUriStr: String,
    ) {
        withContext(DispatchersProvider.io) {
            tracker.trackEvent(
                "setRingtoneToLabelContacts called",
                mapOf("labelId" to "label_contacts", "ringtoneUri" to newRingtoneUriStr)
            )

            labelContacts.forEach { contact ->
                contactRingtoneUpdateHelper.scanAndUpdate(
                    appContext,
                    newRingtoneUriStr,
                    contact.id
                )
            }

            tracker.trackEvent(
                "setRingtoneToLabelContacts completed",
                mapOf("contactCount" to labelContacts.size.toString())
            )
        }
    }

    fun clearAllRingtoneUris() {
        tracker.trackEvent("clearAllRingtoneUris called")

        // 1. Clear ringtones for ALL contacts (without filtering by account type)
        val contactIds = getAllContactIds()
        tracker.trackEvent("Found ${contactIds.size} contacts to update")

        if (contactIds.isNotEmpty()) {
            val contactSelection =
                "${ContactsContract.Contacts._ID} IN (${contactIds.joinToString()})"
            val contactValues =
                ContentValues().apply { putNull(ContactsContract.Contacts.CUSTOM_RINGTONE) }

            try {
                val contactRowsUpdated = appContext.contentResolver.update(
                    ContactsContract.Contacts.CONTENT_URI,
                    contactValues,
                    contactSelection,
                    null
                )
                tracker.trackEvent(
                    "clearAllRingtoneUris - Contacts updated",
                    mapOf("clearedCount" to contactRowsUpdated.toString())
                )
            } catch (e: SecurityException) {
                tracker.trackError(e)
                tracker.trackEvent("clearAllRingtoneUris - Failed due to SecurityException")
            } catch (e: Exception) {
                tracker.trackError(e)
                tracker.trackEvent("clearAllRingtoneUris - Unexpected error: ${e.message}")
            }
        } else {
            tracker.trackEvent("No contacts found for ringtone removal")
        }

        // 2. Clear ringtones for ALL raw contacts (no account type filtering)
        val rawContactIds = getAllRawContactIds()
        tracker.trackEvent("Found ${rawContactIds.size} raw contacts to update")

        if (rawContactIds.isNotEmpty()) {
            val rawContactSelection =
                "${ContactsContract.RawContacts._ID} IN (${rawContactIds.joinToString()})"
            val rawContactValues =
                ContentValues().apply { putNull(ContactsContract.Contacts.CUSTOM_RINGTONE) }

            try {
                val rawContactRowsUpdated = appContext.contentResolver.update(
                    ContactsContract.RawContacts.CONTENT_URI,
                    rawContactValues,
                    rawContactSelection,
                    null
                )
                tracker.trackEvent(
                    "clearAllRingtoneUris - RawContacts updated",
                    mapOf("clearedCount" to rawContactRowsUpdated.toString())
                )
            } catch (e: SecurityException) {
                tracker.trackError(e)
                tracker.trackEvent("clearAllRingtoneUris - Failed due to SecurityException")
            } catch (e: Exception) {
                tracker.trackError(e)
                tracker.trackEvent("clearAllRingtoneUris - Unexpected error: ${e.message}")
            }
        } else {
            tracker.trackEvent("No raw contacts found for ringtone removal")
        }
    }

    /**
     * Get ALL contact IDs (NO filtering by account type)
     */
    private fun getAllContactIds(): List<Long> {
        val contactIds = mutableListOf<Long>()
        val uri = ContactsContract.Contacts.CONTENT_URI
        val projection = arrayOf(ContactsContract.Contacts._ID)

        appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val contactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            while (cursor.moveToNext()) {
                contactIds.add(cursor.getLong(contactIdIndex))
            }
        }

        return contactIds
    }

    /**
     * Get ALL raw contact IDs (NO filtering by account type)
     */
    private fun getAllRawContactIds(): List<Long> {
        val rawContactIds = mutableListOf<Long>()
        val uri = ContactsContract.RawContacts.CONTENT_URI
        val projection = arrayOf(ContactsContract.RawContacts._ID)

        appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val rawContactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
            while (cursor.moveToNext()) {
                rawContactIds.add(cursor.getLong(rawContactIdIndex))
            }
        }

        return rawContactIds
    }

    fun createLabel(labelName: String, accountId: AccountId?): LabelItem? {
        tracker.trackEvent("createLabel called", mapOf("labelName" to labelName))

        val contentValues = ContentValues().apply {
            put(ContactsContract.Groups.TITLE, labelName)

            if (accountId != null) {
                put(ContactsContract.Groups.ACCOUNT_NAME, accountId.name)
                put(ContactsContract.Groups.ACCOUNT_TYPE, accountId.type)
            }
        }

        val labelUri =
            appContext.contentResolver.insert(ContactsContract.Groups.CONTENT_URI, contentValues)

        return labelUri?.let {
            val labelId = ContentUris.parseId(it)

            tracker.trackEvent(
                "createLabel successful",
                mapOf("labelId" to labelId.toString(), "labelName" to labelName)
            )

            triggerSyncForAllAccounts()

            LabelItem(
                id = labelId,
                groupName = labelName,
                contacts = emptyList(),
                ringtoneUriList = emptyList(),
                ringtoneFileName = ""
            )
        }
    }

    suspend fun getDisplayNamesForContactsBatched(
        contactIds: List<Long>,
        batchSize: Int = 200,
    ): Map<Long, String> = withContext(DispatchersProvider.io) {
        if (contactIds.isEmpty()) return@withContext emptyMap()
        val resolver = appContext.contentResolver
        val out = HashMap<Long, String>(contactIds.size)

        contactIds.chunked(batchSize).forEach { chunk ->
            val selection = "${ContactsContract.Contacts._ID} IN (${chunk.joinToString(",")})"
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            )
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection, selection, null, null
            )?.use { c ->
                val idxId = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val idxName =
                    c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                while (c.moveToNext()) {
                    val id = c.getLong(idxId)
                    val name = c.getString(idxName) ?: ""
                    if (name.isNotBlank()) out[id] = name
                }
            }
        }
        out
    }

    suspend fun getPrimaryPhonesForContactsBatched(
        contactIds: List<Long>,
        batchSize: Int = 200,
    ): Map<Long, String?> = withContext(DispatchersProvider.io) {
        if (contactIds.isEmpty()) return@withContext emptyMap()
        val resolver = appContext.contentResolver
        val out = HashMap<Long, String?>()

        contactIds.chunked(batchSize).forEach { chunk ->
            val selection =
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} IN (${chunk.joinToString(",")})"
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
            )
            val sortOrder =
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} ASC, " +
                        "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, " +
                        "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC, " +
                        "${ContactsContract.CommonDataKinds.Phone._ID} ASC"

            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, selection, null, sortOrder
            )?.use { c ->
                val idxId =
                    c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val idxNum = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val id = c.getLong(idxId)
                    if (out.containsKey(id)) continue
                    out[id] = c.getString(idxNum)
                }
            }
        }
        out
    }

    private fun triggerSyncForAllAccounts() {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true)
        }
        try {
            ContentResolver.requestSync(null, ContactsContract.AUTHORITY, extras)
            tracker.trackEvent("Sync successful")
        } catch (t: Throwable) {
            tracker.trackError(t)
        }
    }

    private fun removeAllContactsFromLabel(labelId: Long) {
        tracker.trackEvent(
            "removeAllContactsFromLabel called",
            mapOf("labelId" to labelId.toString())
        )

        // Query all contacts associated with this label
        val uri = ContactsContract.Data.CONTENT_URI
        val selection =
            "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ? AND " +
                    "${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(
            labelId.toString(),
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
        )

        val rowsDeleted = appContext.contentResolver.delete(uri, selection, selectionArgs)

        triggerSyncForAllAccounts()
        tracker.trackEvent(
            "removeAllContactsFromLabel completed",
            mapOf("labelId" to labelId.toString(), "removedCount" to rowsDeleted.toString())
        )
    }

    private fun addSingleContactToLabel(labelId: Long, contactId: Long) {
        tracker.trackEvent(
            "addSingleContactToLabel called",
            mapOf("labelId" to labelId.toString(), "contactId" to contactId.toString())
        )

        // Get the RAW_CONTACT_ID from CONTACT_ID
        val rawContactId = getRawContactIdForContact(contactId)
            ?: throw IllegalArgumentException("raw_contact_id is required and could not be found for contactId: $contactId")

        val ops = ArrayList<ContentProviderOperation>().apply {
            add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(
                        ContactsContract.Data.RAW_CONTACT_ID, // Use RAW_CONTACT_ID instead
                        rawContactId
                    )
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE // Group membership MIME type
                    )
                    .withValue(
                        ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, // Link to the label
                        labelId
                    )
                    .build()
            )
        }

        appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun getRawContactIdForContact(contactId: Long): Long? {
        val uri = ContactsContract.RawContacts.CONTENT_URI
        val projection = arrayOf(ContactsContract.RawContacts._ID)
        val selection = "${ContactsContract.RawContacts.CONTACT_ID} = ?"
        val selectionArgs = arrayOf(contactId.toString())

        appContext.contentResolver.query(uri, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID))
                }
            }
        return null
    }

    private fun getContactsForLabel(labelId: Long): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val uri = ContactsContract.Data.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.GroupMembership.CONTACT_ID,
            ContactsContract.Data.DISPLAY_NAME_PRIMARY
        )
        val selection =
            "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(
            labelId.toString(),
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
        )

        appContext.contentResolver.query(uri, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                val contactIdIndex =
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.GroupMembership.CONTACT_ID)
                val nameIndex =
                    cursor.getColumnIndexOrThrow(ContactsContract.Data.DISPLAY_NAME_PRIMARY)

            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(contactIdIndex) // Use this for accurate phone lookup
                val name = cursor.getString(nameIndex).orEmpty()
                val phone =
                    appContext.getPrimaryPhoneNumberForContact(contactId) // Use CONTACT_ID for phone query
                val ringtoneUriStr = getCustomRingtoneForContact(contactId)

                contacts.add(
                    Contact(
                        id = contactId,
                        name = name,
                        phone = phone,
                        ringtoneUriStr = ringtoneUriStr
                    )
                )
            }
        }

        return contacts
    }

    private fun getCustomRingtoneForContact(contactId: Long): String? {
        val contactUri =
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val projection = arrayOf(ContactsContract.Contacts.CUSTOM_RINGTONE)

        appContext.contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.CUSTOM_RINGTONE))
            }
        }
        return null
    }

    suspend fun getAllLabelItemsForAccounts(selectedAccounts: AccountId): List<LabelItem> =
        withContext(DispatchersProvider.io) {
            // ⬇️ paste your current filtered labels query
            val pairs = listOf(selectedAccounts.type to selectedAccounts.name)
            if (pairs.isEmpty()) return@withContext getAllLabelItems()

            val where = buildString {
                append("${ContactsContract.Groups.DELETED}=0 AND (")
                pairs.forEachIndexed { idx, _ ->
                    if (idx > 0) append(" OR ")
                    append("(${ContactsContract.Groups.ACCOUNT_TYPE}=? AND ${ContactsContract.Groups.ACCOUNT_NAME}=?)")
                }
                append(")")
            }
            val args = pairs.flatMap { listOf(it.first, it.second) }.toTypedArray()

            val allowedGroupIds = linkedSetOf<Long>()
            val projection = arrayOf(ContactsContract.Groups._ID)

            val cr = appContext.contentResolver
            cr.query(
                ContactsContract.Groups.CONTENT_URI,
                projection,
                where,
                args,
                null
            )?.use { c ->
                val idxId = c.getColumnIndexOrThrow(ContactsContract.Groups._ID)
                while (c.moveToNext()) {
                    allowedGroupIds.add(c.getLong(idxId))
                }
            }

            if (allowedGroupIds.isEmpty()) return@withContext emptyList()

            val all = getAllLabelItems()

            return@withContext all.filter { it.id in allowedGroupIds }
        }
}

internal fun Context.getPrimaryPhoneNumberForContact(contactId: Long): String? {
    val phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
    val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
    val selectionArgs = arrayOf(contactId.toString())

    contentResolver.query(phoneUri, projection, selection, selectionArgs, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
            }
        }
    return null
}

private fun List<String>.stringifyContacts(preferenceHelper: EncryptedPreferencesHelper): String =
    takeIf { it.isNotEmpty() }
        ?.mapNotNull { preferenceHelper.getString(it) }
        ?.filter { it.isNotBlank() }
        ?.joinToString()
        .orEmpty()
