package com.milen.grounpringtonesetter.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactRingtoneUpdateHelper(
    private val tracker: Tracker,
    private val preferenceHelper: EncryptedPreferencesHelper,
) {

    suspend fun scanAndUpdate(context: Context, ringtoneStr: String, contactId: Long) {
        val sourceUri = Uri.parse(ringtoneStr)

        val copiedUri = withContext(Dispatchers.IO) {
            copyRingtoneToScopedMedia(context, sourceUri)
        } ?: run {
            tracker.trackError(IllegalStateException("Failed to copy ringtone to public storage"))
            return
        }

        val updateSuccess = tryUpdateCustomRingtone(context, contactId, copiedUri.toString())

        if (!updateSuccess) {
            tracker.trackError(RuntimeException("Failed to update CUSTOM_RINGTONE in scanAndUpdate for: $copiedUri"))
        } else {
            verifyCustomRingtoneSet(context, contactId, copiedUri.toString())
        }
    }

    private suspend fun copyRingtoneToScopedMedia(context: Context, uri: Uri): Uri? =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val rawName = uri.lastPathSegment?.substringAfterLast('/')
                    ?: "ringtone_${System.currentTimeMillis()}.mp3"
                val fileName = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp3")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES)
                    put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                }

                val externalUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val newUri =
                    context.contentResolver.insert(externalUri, values) ?: return@withContext null

                context.contentResolver.openOutputStream(newUri)?.use { outputStream ->
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.copyTo(outputStream)
                    } ?: return@withContext null
                } ?: return@withContext null

                // associate the newUri with the song name of the current uri
                preferenceHelper.saveString(
                    newUri.toString(),
                    preferenceHelper.getString(uri.toString()).orEmpty()
                )

                newUri
            } catch (e: Exception) {
                tracker.trackError(RuntimeException("Failed to copy ringtone to MediaStore: ${e.message}"))
                null
            }
        }

    private fun verifyCustomRingtoneSet(context: Context, contactId: Long, expectedUri: String) {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.Contacts.CUSTOM_RINGTONE),
            null,
            null,
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val actual = it.getString(
                    it.getColumnIndexOrThrow(ContactsContract.Contacts.CUSTOM_RINGTONE)
                )
                if (actual != expectedUri) {
                    tracker.trackError(IllegalStateException("CUSTOM_RINGTONE mismatch. Expected: $expectedUri but found: $actual"))
                }
            } else {
                tracker.trackError(RuntimeException("Failed to query CUSTOM_RINGTONE for contact: $contactId"))
            }
        }
    }

    private fun tryUpdateCustomRingtone(
        context: Context,
        contactId: Long,
        ringtoneUri: String,
    ): Boolean {
        val contactUri =
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val values = ContentValues().apply {
            put(ContactsContract.Contacts.CUSTOM_RINGTONE, ringtoneUri)
        }
        val rows = context.contentResolver.update(contactUri, values, null, null)
        return rows > 0
    }
}
