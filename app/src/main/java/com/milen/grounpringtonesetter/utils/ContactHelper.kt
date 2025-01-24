package com.milen.grounpringtonesetter.utils

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.exceptions.NoContactsFoundException
import java.util.concurrent.CountDownLatch

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

    fun setRingtoneToLabelContacts(
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
        AccountManager.get(appContext).accounts.filter { it.type == "com.google" }

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

    private fun scanAndUpdate(context: Context, ringtoneStr: String, contactId: Long) {
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
        latch.await()

        val finalRingtoneUri = scannedUri?.toString() ?: uriFromStr.toString().also {
            it.trackErrorIfEmpty(tracker, "uri: $scannedUri and ringtoneStr: $ringtoneStr")
        }

        // Attempt to update the contact with the new ringtone
        try {
            val updateUri = ContentUris.withAppendedId(
                ContactsContract.Contacts.CONTENT_URI,
                contactId
            )

            val rowsUpdated = context.contentResolver.update(
                updateUri,
                ContentValues().apply {
                    put(ContactsContract.Contacts.CUSTOM_RINGTONE, finalRingtoneUri)
                },
                null,
                null
            )

            if (rowsUpdated == 0) {
                tracker.trackError(
                    IllegalArgumentException(
                        "scanAndUpdate error: No rows updated, device may forbid edits to ContactsContract or contact is read-only."
                    )
                )
            }
        } catch (ex: Throwable) {
            tracker.trackError(ex)
        }
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
