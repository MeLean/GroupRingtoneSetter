package com.milen.grounpringtonesetter.utils

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.exceptions.NoContactsFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.resume

class ContactsHelper(
    private val appContext: Application,
    private val preferenceHelper: EncryptedPreferencesHelper,
    private val tracker: Tracker,
) {

    fun getAllPhoneContacts(): List<Contact> {
        tracker.trackEvent("getAllPhoneContacts called")
        val contacts = mutableListOf<Contact>()
        val uri = ContactsContract.Contacts.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.Contacts._ID, // Using the appropriate Contacts _ID
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.CUSTOM_RINGTONE
        )

        appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIndex =
                cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val ringtoneIndex =
                cursor.getColumnIndexOrThrow(ContactsContract.Contacts.CUSTOM_RINGTONE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex).orEmpty()
                val phone = getPrimaryPhoneNumberForContact(id) // Fetch phone number
                val ringtoneUriStr = cursor.getString(ringtoneIndex)

                contacts.add(
                    Contact(
                        id = id,
                        name = name,
                        phone = phone,
                        ringtoneUriStr = ringtoneUriStr
                    )
                )
            }
        } ?: throw NoContactsFoundException()

        return contacts.also {
            tracker.trackEvent(
                "getAllPhoneContacts loaded",
                mapOf("count" to it.size.toString())
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

    fun getAllLabelItems(includeDeviceContacts: Boolean = true): List<LabelItem> {
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

        return labels
    }

    suspend fun setRingtoneToLabelContacts(
        labelContacts: List<Contact>,
        newRingtoneUriStr: String,
    ) {
        tracker.trackEvent(
            "setRingtoneToLabelContacts called",
            mapOf("labelId" to "label_contacts", "ringtoneUri" to newRingtoneUriStr)
        )

        labelContacts.forEach { contact ->
            scanAndUpdate(appContext, newRingtoneUriStr, contact.id)
        }

        tracker.trackEvent(
            "setRingtoneToLabelContacts completed",
            mapOf("contactCount" to labelContacts.size.toString())
        )
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

    fun createLabel(labelName: String, account: Account?): LabelItem? {
        tracker.trackEvent("createLabel called", mapOf("labelName" to labelName))

        // Prepare ContentValues with account details
        val contentValues = ContentValues().apply {
            put(ContactsContract.Groups.TITLE, labelName)

            if (account != null) {
                put(ContactsContract.Groups.ACCOUNT_NAME, account.name)
                put(ContactsContract.Groups.ACCOUNT_TYPE, account.type)
            }
        }

        // Insert the label into the ContactsContract.Groups table
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

    fun getGoogleAccounts(): List<Account> =
        AccountManager.get(appContext).accounts.toList()

    private fun triggerSyncForAllAccounts() {
        val accounts = AccountManager.get(appContext).accounts

        accounts.forEach { account ->
            if (account.type == "com.google") {
                // Ensure sync is enabled for the account and Contacts authority
                if (!ContentResolver.getSyncAutomatically(account, ContactsContract.AUTHORITY)) {
                    ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)
                }

                // Trigger a manual sync
                val extras = Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true) // Force manual sync
                    putBoolean(
                        ContentResolver.SYNC_EXTRAS_EXPEDITED,
                        true
                    ) // Expedite the sync process
                }
                ContentResolver.requestSync(account, ContactsContract.AUTHORITY, extras)
            }
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

    private fun updateRingtoneForContactAndRawContacts(
        contactId: Long,
        ringtoneUri: String,
    ): Boolean {
        val contactUpdated = updateContactRingtone(contactId, ringtoneUri)
        val rawContactsUpdated = updateWritableRawContactsRingtone(contactId, ringtoneUri)

        return contactUpdated || rawContactsUpdated // Return true if at least one was updated
    }

    private fun updateContactRingtone(contactId: Long, ringtoneUri: String): Boolean {
        val updateUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)

        val rowsUpdated = appContext.contentResolver.update(
            updateUri,
            ContentValues().apply {
                put(ContactsContract.Contacts.CUSTOM_RINGTONE, ringtoneUri)
            },
            null,
            null
        )

        return rowsUpdated > 0
    }

    private fun updateWritableRawContactsRingtone(contactId: Long, ringtoneUri: String): Boolean {
        val rawContactUri = ContactsContract.RawContacts.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.ACCOUNT_TYPE
        )
        val selection = "${ContactsContract.RawContacts.CONTACT_ID} = ?"
        val selectionArgs = arrayOf(contactId.toString())

        var anyUpdated = false

        appContext.contentResolver.query(rawContactUri, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                val rawContactIdIndex =
                    cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)

                while (cursor.moveToNext()) {
                    val rawContactId = cursor.getLong(rawContactIdIndex)
                    try {
                        anyUpdated =
                            anyUpdated || updateRawContactRingtone(rawContactId, ringtoneUri)
                    } catch (e: SecurityException) {
                        tracker.trackError(e)
                    }
                }
            }

        return anyUpdated
    }


    private fun updateRawContactRingtone(rawContactId: Long, ringtoneUri: String): Boolean {
        val rawContactUri =
            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId)

        val rowsUpdated = appContext.contentResolver.update(
            rawContactUri,
            ContentValues().apply {
                put(ContactsContract.Contacts.CUSTOM_RINGTONE, ringtoneUri)
            },
            null,
            null
        )

        return rowsUpdated > 0
    }

    private suspend fun scanAndUpdate(context: Context, ringtoneStr: String, contactId: Long) {
        val uriFromStr = Uri.parse(ringtoneStr)

        // Validate the ringtone URI path
        val filePath = uriFromStr.path.orEmpty()
        if (filePath.isEmpty()) {
            tracker.trackError(IllegalArgumentException("Invalid file path for ringtoneStr: $ringtoneStr"))
            return
        }

        // Use a CountDownLatch to wait for the media scan result
        val latch = CountDownLatch(1)
        var scannedUri: Uri? = null

        MediaScannerConnection.scanFile(
            context,
            arrayOf(filePath),
            arrayOf("audio/*")
        ) { _, uri ->
            tracker.trackEvent("scanAndUpdate scanned: $ringtoneStr")
            scannedUri = uri
            latch.countDown()
        }

        // Wait for the scan result
        withContext(Dispatchers.IO) {
            latch.await()

            val finalRingtoneUri: String = scannedUri?.toString() ?: uriFromStr.toString().also {
                it.trackErrorIfEmpty(tracker, "uri: $scannedUri and ringtoneStr: $ringtoneStr")
            }

            try {
                val updateDone: Boolean =
                    updateRingtoneForContactAndRawContacts(contactId, finalRingtoneUri)

                if (updateDone.not()) {
                    tracker.trackError(
                        IllegalArgumentException(
                            "scanAndUpdate error: No rows updated, device may forbid edits to ContactsContract or contact is read-only."
                        )
                    )

                    fallbackUpdateCustomRingtone(
                        context = context,
                        contactId = contactId,
                        finalRingtoneUri = finalRingtoneUri,
                        tracker = tracker
                    )
                }
            } catch (ex: Throwable) {
                tracker.trackError(ex)
            }
        }
    }

    private suspend fun fallbackUpdateCustomRingtone(
        context: Context,
        contactId: Long,
        finalRingtoneUri: String,
        tracker: Tracker,
    ): Boolean {
        // 1. Get the original contact's display name and phone number
        val displayName = getDisplayNameForContact(contactId)
        val phoneNumber = getPrimaryPhoneNumberForContact(contactId)

        // 2. Insert a new raw contact under your custom account
        val rawContactValues = ContentValues().apply {
            put(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_STR)
            put(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_STR)
        }
        val rawContactUri = context.contentResolver.insert(
            ContactsContract.RawContacts.CONTENT_URI,
            rawContactValues
        )
        if (rawContactUri == null) {
            tracker.trackError(IllegalStateException("Failed to create a new raw contact"))
            return false
        }
        val rawContactId = ContentUris.parseId(rawContactUri)

        // 3. Insert original contact details for better aggregation
        val dataValues = mutableListOf<ContentValues>().apply {
            // Add Display Name
            add(ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                )
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
            })

            // Add Phone Number if available
            phoneNumber?.let {
                add(ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    put(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                    )
                    put(ContactsContract.CommonDataKinds.Phone.NUMBER, it)
                })
            }
        }

        // Insert the contact details into ContactsContract.Data
        dataValues.forEach { contentValues ->
            context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, contentValues)
        }

        // 4. Wait for aggregation using the ContentObserver approach
        val aggregatedContactId = waitForAggregatedContactId(context, rawContactId)
        if (aggregatedContactId == null || aggregatedContactId == -1L) {
            // Cleanup if aggregation fails
            context.contentResolver.delete(rawContactUri, null, null)
            tracker.trackError(IllegalStateException("Failed to retrieve aggregated contact ID for raw contact"))
            return false
        }

        // 5. Update the aggregated contact with the custom ringtone
        val newUpdateUri = ContentUris.withAppendedId(
            ContactsContract.Contacts.CONTENT_URI,
            aggregatedContactId
        )
        val newRowsUpdated = context.contentResolver.update(
            newUpdateUri,
            ContentValues().apply {
                put(ContactsContract.Contacts.CUSTOM_RINGTONE, finalRingtoneUri)
            },
            null,
            null
        )

        return if (newRowsUpdated == 0) {
            tracker.trackError(IllegalArgumentException("Fallback update error: No rows updated for new contact. AggregatedContactId: $aggregatedContactId"))
            false
        } else {
            tracker.trackEvent("Fallback update successful for new raw contact linked to contactId: $contactId")
            true
        }
    }

    private fun getDisplayNameForContact(contactId: Long): String {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val projection = arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)

        appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY))
            }
        }
        return "Unnamed Contact"
    }

    private suspend fun waitForAggregatedContactId(
        context: Context,
        rawContactId: Long,
        timeoutMillis: Long = 5000L,
    ): Long? {
        return suspendCancellableCoroutine { cont ->
            val resolver: ContentResolver = context.contentResolver
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    queryAggregatedContactId(context, rawContactId)?.let { aggregatedId ->
                        if (aggregatedId != -1L) {
                            resolver.unregisterContentObserver(this)
                            if (cont.isActive) {
                                cont.resume(aggregatedId)
                            }
                        }
                    }
                }
            }

            // Register the observer
            resolver.registerContentObserver(
                ContactsContract.RawContacts.CONTENT_URI,
                true,
                observer
            )

            // Check immediately in case aggregation has already happened
            queryAggregatedContactId(context, rawContactId)?.let { aggregatedId ->
                if (aggregatedId != -1L) {
                    resolver.unregisterContentObserver(observer)
                    if (cont.isActive) {
                        cont.resume(aggregatedId)
                    }
                }
            }

            // Set up a timeout
            Handler(Looper.getMainLooper()).postDelayed({
                if (cont.isActive) {
                    resolver.unregisterContentObserver(observer)
                    cont.resume(null)
                }
            }, timeoutMillis)

            // Ensure that the observer is unregistered if the coroutine is cancelled
            cont.invokeOnCancellation {
                resolver.unregisterContentObserver(observer)
            }
        }
    }

    private fun queryAggregatedContactId(context: Context, rawContactId: Long): Long? {
        val projection = arrayOf(ContactsContract.RawContacts.CONTACT_ID)
        val selection = "${ContactsContract.RawContacts._ID} = ?"
        val selectionArgs = arrayOf(rawContactId.toString())
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID))
            }
        }
        return null
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
                    getPrimaryPhoneNumberForContact(contactId) // Use CONTACT_ID for phone query
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

    private fun getPrimaryPhoneNumberForContact(contactId: Long): String? {
        val phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        val selectionArgs = arrayOf(contactId.toString())

        appContext.contentResolver.query(phoneUri, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                }
            }
        return null
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

    companion object {
        private const val ACCOUNT_STR = "com.milen.grounpringtonesetter"
    }
}

private fun String.trackErrorIfEmpty(tracker: Tracker, rawStr: String) =
    this.takeIf { it.isEmpty() }
        ?.let { tracker.trackError(IllegalArgumentException("scanAndUpdate empty uri for $rawStr")) }

private fun List<String>.stringifyContacts(preferenceHelper: EncryptedPreferencesHelper): String =
    takeIf { it.isNotEmpty() }
        ?.mapNotNull { preferenceHelper.getString(it) }
        ?.filter { it.isNotBlank() }
        ?.joinToString()
        .orEmpty()
