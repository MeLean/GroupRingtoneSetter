package com.milen.grounpringtonesetter.utils

import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.database.getStringOrNull
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.GroupItem
import com.milen.grounpringtonesetter.data.exceptions.NoContactsFoundException

class ContactsHelper(private val appContext: Application) {

    fun getAllPhoneContacts(): List<Contact> {
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
                            ringtoneUriString = ringtoneStr,
                        )
                    )
                }
            } ?: throw NoContactsFoundException()


        return contacts
    }

    //TODO CHECK
    private fun getDeviceStoredRawContactIds(): List<Long> {
        val rawContactIds = mutableListOf<Long>()
        val projection = arrayOf(ContactsContract.RawContacts._ID)
        val selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL"

        appContext.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            projection,
            selection,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                rawContactIds.add(id)
            }
        }

        return rawContactIds
    }

    fun updateGroupName(groupId: Long, newGroupName: String) {
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
        val operations = ArrayList<ContentProviderOperation>()
        val groupUri = ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, groupId)
        operations.add(
            ContentProviderOperation.newDelete(groupUri).build()
        )

        try {
            appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addAllContactsToGroup(groupId: Long, includedContacts: List<Contact>) {
        includedContacts.forEach {
            addSingleContactToGroup(groupId = groupId, contactId = it.id)
        }
    }

    fun removeAllContactsFromGroup(groupId: Long, excludedContacts: List<Contact>) {
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

        try {
            appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getAllGroups(): List<GroupItem> {
        val groups = mutableListOf<GroupItem>()
        val uri = ContactsContract.Groups.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.Groups._ID,
            ContactsContract.Groups.TITLE,
            ContactsContract.Groups.GROUP_IS_READ_ONLY
        )

        appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups._ID)
            val titleIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)
            val readOnlyIndex =
                cursor.getColumnIndexOrThrow(ContactsContract.Groups.GROUP_IS_READ_ONLY)

            while (cursor.moveToNext()) {
                val isReadOnly = cursor.getInt(readOnlyIndex) > 0
                if (!isReadOnly) {
                    val id = cursor.getLong(idIndex)
                    val title = cursor.getString(titleIndex)
                    val contacts = getContactsForGroup(id)

                    groups.add(
                        GroupItem(
                            id = id,
                            groupName = title,
                            contacts = contacts,
                            ringtoneUri = contacts.allContactsHaveSameRingtoneUri()
                        )
                    )
                }
            }
        }

        return groups
    }

    fun setRingtoneToGroup(
        contentResolver: ContentResolver,
        groupId: Long,
        newRingtoneUri: Uri
    ): Unit =
        getContactsForGroup(groupId).run {
            ContentValues().apply {
                put(ContactsContract.Contacts.CUSTOM_RINGTONE, newRingtoneUri.toString())
            }.also {
                forEach { contact ->
                    contentResolver.update(
                        ContentUris.withAppendedId(
                            ContactsContract.Contacts.CONTENT_URI,
                            contact.id
                        ),
                        it,
                        null,
                        null
                    )
                }
            }
        }

    fun createGroup(groupName: String) {
        val values = ContentValues().apply {
            put(ContactsContract.Groups.TITLE, groupName)
        }

        try {
            appContext.contentResolver.insert(ContactsContract.Groups.CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e("TEST_IT", "Error creating group", e)
        }
    }

    private fun addSingleContactToGroup(groupId: Long, contactId: Long) {
        val ops = ArrayList<ContentProviderOperation>().apply {
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

        try {
            val results = appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Log.d("TEST_IT", "Operation results: ${results.joinToString()}")
        } catch (e: Exception) {
            Log.e("TEST_IT", "Error adding single contact to group", e)
        }
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
                        ringtoneUriString = ringtoneUriStr
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

fun List<Contact>.allContactsHaveSameRingtoneUri(): Uri? =
    this.firstOrNull()?.ringtoneUri?.let { ringtoneUri ->
        if (all { contact -> contact.ringtoneUri == ringtoneUri })
            ringtoneUri else null
    }
