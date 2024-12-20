package com.milen.grounpringtonesetter.utils

import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.database.getStringOrNull
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.GroupItem
import com.milen.grounpringtonesetter.data.exceptions.NoContactsFoundException

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
            ContactsContract.RawContacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.CUSTOM_RINGTONE
        )

        appContext.contentResolver.query(uri, projection, null, null, null)
            ?.use {
                val idIndex =
                    it.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
                val nameIndex =
                    it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val customRingToneIndex =
                    it.getColumnIndexOrThrow(ContactsContract.Contacts.CUSTOM_RINGTONE)

                while (it.moveToNext()) {
                    val id = it.getLong(idIndex)
                    val name = it.getString(nameIndex)
                    val phone = getPrimaryPhoneNumberForContact(id)
                    val ringtoneStr = it.getString(customRingToneIndex)

                    contacts.add(
                        Contact(
                            id = id,
                            name = name,
                            phone = phone,
                            ringtoneUriStr = ringtoneStr,
                        )
                    )
                }
            } ?: throw NoContactsFoundException()


        return contacts
            .also {
                tracker.trackEvent(
                    "getAllPhoneContacts loaded",
                    mapOf("count" to it.size.toString())
                )
            }
    }

    fun updateGroupName(groupId: Long, newGroupName: String) {
        tracker.trackEvent("updateGroupName called")
        val groupNameValues = ContentValues().apply {
            put(ContactsContract.Groups.TITLE, newGroupName)
        }

        val selection = ContactsContract.Groups._ID + "=?"
        val selectionArgs = arrayOf(groupId.toString())

        appContext.contentResolver.update(
            ContactsContract.Groups.CONTENT_URI,
            groupNameValues,
            selection,
            selectionArgs
        )
    }

    fun deleteGroup(groupId: Long) {
        tracker.trackEvent("deleteGroup called")
        val operations = ArrayList<ContentProviderOperation>()
        val groupUri = ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, groupId)
        operations.add(ContentProviderOperation.newDelete(groupUri).build())

        appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
    }

    fun addAllContactsToGroup(groupId: Long, includedContacts: List<Contact>): Unit =
        includedContacts.forEach {
            addSingleContactToGroup(groupId = groupId, contactId = it.id)
        }.also {
            tracker.trackEvent("addAllContactsToGroup called")
        }

    fun removeAllContactsFromGroup(groupId: Long, excludedContacts: List<Contact>) {
        tracker.trackEvent("removeAllContactsFromGroup called")
        val ops = ArrayList<ContentProviderOperation>()

        excludedContacts.forEach { contact ->
            val selection =
                "${ContactsContract.CommonDataKinds.GroupMembership.RAW_CONTACT_ID} = ? AND " +
                        "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ? AND " +
                        "${ContactsContract.CommonDataKinds.GroupMembership.MIMETYPE} = ?"
            val selectionArgs = arrayOf(
                contact.id.toString(),
                groupId.toString(),
                ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
            )

            ops.add(
                ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection(selection, selectionArgs)
                    .build()
            )
        }

        appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    fun getAllGroups(): List<GroupItem> {
        tracker.trackEvent("getAllGroups called")
        val groups = mutableListOf<GroupItem>()
        val uri = ContactsContract.Groups.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.Groups._ID,
            ContactsContract.Groups.TITLE,
            ContactsContract.Groups.GROUP_IS_READ_ONLY,
            ContactsContract.Groups.DELETED
        )

        val selection =
            "${ContactsContract.Groups.DELETED} = 0 AND ${ContactsContract.Groups.GROUP_IS_READ_ONLY} = 0"

        appContext.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups._ID)
            val titleIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val title = cursor.getString(titleIndex)
                val contacts = getContactsForGroup(id)

                val contactsUri: List<String> = contacts.mapNotNull { it.ringtoneUriStr }.distinct()

                groups.add(
                    GroupItem(
                        id = id,
                        groupName = title,
                        contacts = contacts,
                        ringtoneUriStr = contactsUri,
                        ringtoneFileName = contactsUri.stringifyContacts(preferenceHelper)
                    )
                )
            }
        }

        return groups
    }

    fun setRingtoneToGroupContacts(
        groupContacts: List<Contact>,
        newRingtoneUriStr: String
    ): Unit =
        groupContacts.forEach { contact ->
            scanAndUpdate(appContext, newRingtoneUriStr, contact.id)
        }


    fun createGroup(groupName: String): GroupItem? =
        appContext.contentResolver.insert(
            ContactsContract.Groups.CONTENT_URI,
            ContentValues().apply { put(ContactsContract.Groups.TITLE, groupName) }
        )?.let {
            tracker.trackEvent("createGroup called")
            GroupItem(
                id = ContentUris.parseId(it),
                groupName = groupName,
                contacts = emptyList()
            )
        }

    private fun scanAndUpdate(context: Context, ringtoneStr: String, contactId: Long) {
        val uriFromStr = Uri.parse(ringtoneStr)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(uriFromStr?.path),
            null
        ) { _, uri ->
            tracker.trackEvent("scanAndUpdate called")
            appContext.contentResolver.update(
                ContentUris.withAppendedId(
                    ContactsContract.Contacts.CONTENT_URI,
                    contactId
                ),
                ContentValues().apply {
                    put(
                        ContactsContract.Contacts.CUSTOM_RINGTONE,
                        uri?.toString() ?: uriFromStr?.toString().orEmpty()
                    )
                },
                null,
                null
            )
        }
    }

    private fun addSingleContactToGroup(groupId: Long, contactId: Long) {
        val ops = ArrayList<ContentProviderOperation>().apply {
            tracker.trackEvent("addSingleContactToGroup called")
            add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, contactId)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
                    )
                    .withValue(
                        ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID,
                        groupId
                    )
                    .build()
            )
        }

        appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun getContactsForGroup(groupId: Long): List<Contact> {
        val contacts = mutableListOf<Contact>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.GroupMembership.RAW_CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.Contacts.CUSTOM_RINGTONE,
        )

        val selection = "${ContactsContract.CommonDataKinds.GroupMembership.MIMETYPE} = ? AND " +
                "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?"
        val selectionArgs = arrayOf(
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
            groupId.toString()
        )

        appContext.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val rawContactIdIndex =
                cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.GroupMembership.RAW_CONTACT_ID)
            val displayNameIndex =
                cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val customRingToneIndex =
                cursor.getColumnIndexOrThrow(ContactsContract.Contacts.CUSTOM_RINGTONE)

            while (cursor.moveToNext()) {
                val rawContactId = cursor.getLong(rawContactIdIndex)
                val displayName = cursor.getString(displayNameIndex)
                val phone = getPrimaryPhoneNumberForContact(rawContactId)
                val ringtoneUriStr = cursor.getString(customRingToneIndex)

                contacts.add(
                    Contact(
                        id = rawContactId,
                        name = displayName,
                        phone = phone,
                        ringtoneUriStr = ringtoneUriStr
                    )
                )
            }
        }

        return contacts
    }

    private fun getPrimaryPhoneNumberForContact(rawContactId: Long): String? {
        val phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val phoneProjection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val phoneSelection = "${ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID} = ?"
        val phoneSelectionArgs = arrayOf(rawContactId.toString())

        appContext.contentResolver.query(
            phoneUri,
            phoneProjection,
            phoneSelection,
            phoneSelectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getStringOrNull(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
            }
        }
        return null
    }
}

private fun List<String>.stringifyContacts(preferenceHelper: EncryptedPreferencesHelper): String =
    takeIf { it.isNotEmpty() }
        ?.mapNotNull { preferenceHelper.getString(it) }
        ?.filter { it.isNotBlank() }
        ?.joinToString()
        .orEmpty()
