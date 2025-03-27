package com.milen.grounpringtonesetter.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactRingtoneUpdateHelper(
    private val tracker: Tracker,
    private val preferenceHelper: EncryptedPreferencesHelper,
) {

    /**
     * Main method to scan, optionally copy, and set a ringtone for a specific contact.
     * Reuses existing ringtone if already in MediaStore by filename.
     * If copying fails, falls back to using the original picked URI directly.
     * Stores a mapping from URI string to filename for display purposes.
     */
    suspend fun scanAndUpdate(context: Context, ringtoneStr: String, contactId: Long) {
        val sourceUri = Uri.parse(ringtoneStr)

        // Validate the input URI string
        if (ringtoneStr.isBlank() || sourceUri == Uri.EMPTY) {
            tracker.trackError(IllegalArgumentException("Invalid ringtone URI: $ringtoneStr"))
            return
        }

        // Try to find existing file or copy it to public MediaStore
        val copiedUri = withContext(Dispatchers.IO) {
            copyRingtoneToScopedMedia(context, sourceUri)
        }

        // If copying failed, use the picked source URI as a fallback
        val uriToSet = copiedUri?.toString() ?: sourceUri.toString()

        // Store filename for the used URI so we can display it later
        val fileName = getNormalizedFileName(context, sourceUri)
        preferenceHelper.saveString(uriToSet, fileName)

        // Try to set the URI as the contact's custom ringtone
        val updateSuccess = try {
            tryUpdateCustomRingtone(context, contactId, uriToSet)
        } catch (e: Exception) {
            tracker.trackError(
                RuntimeException(
                    "Failed during tryUpdateCustomRingtone: ${e.message}",
                    e
                )
            )
            false
        }

        // Log failure or verify it was successfully set
        if (!updateSuccess) {
            tracker.trackError(RuntimeException("Failed to update CUSTOM_RINGTONE in scanAndUpdate for: $uriToSet"))
        } else {
            try {
                verifyCustomRingtoneSet(context, contactId, uriToSet)
            } catch (e: Exception) {
                tracker.trackError(
                    RuntimeException(
                        "Failed during verifyCustomRingtoneSet: ${e.message}",
                        e
                    )
                )
            }

            // Optionally persist URI permission if fallback was used
            if (copiedUri == null) {
                tryPersistUriPermission(context, sourceUri)
            }
        }
    }

    /**
     * Attempts to find an existing ringtone by filename in the MediaStore.
     * If not found, copies the ringtone to the public MediaStore ringtones folder.
     */
    private suspend fun copyRingtoneToScopedMedia(context: Context, uri: Uri): Uri? =
        withContext(Dispatchers.IO) {
            try {
                val fileName = getNormalizedFileName(context, uri)

                // 🔍 Check if file with the same name already exists in MediaStore
                findExistingRingtoneUri(context, fileName)?.let { return@withContext it }

                // ❌ If not found, proceed to copy to MediaStore
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp3")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES)
                    put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                }

                val newUri = context.contentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return@withContext null

                context.contentResolver.openOutputStream(newUri)?.use { output ->
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.copyTo(output)
                    } ?: return@withContext null
                } ?: return@withContext null

                return@withContext newUri
            } catch (e: Exception) {
                tracker.trackError(
                    RuntimeException(
                        "Failed to reuse or copy ringtone: ${e.message}",
                        e
                    )
                )
                null
            }
        }

    /**
     * Generates a normalized file name by trying to get DISPLAY_NAME from content resolver,
     * and falls back to URI path if necessary.
     */
    private fun getNormalizedFileName(context: Context, uri: Uri): String {
        // Try to read display name from content resolver
        if (uri.scheme == "content") {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                }
            }
        }
        // Fallback to URI path
        val rawName = uri.lastPathSegment?.substringAfterLast('/') ?: "ringtone.mp3"
        return rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    /**
     * Tries to find an existing ringtone in MediaStore by file name.
     */
    private fun findExistingRingtoneUri(context: Context, fileName: String): Uri? {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME
        )
        val selection =
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(fileName, "${Environment.DIRECTORY_RINGTONES}/")

        val resolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(idColumn)
                return ContentUris.withAppendedId(uri, id)
            }
        }

        return null
    }

    /**
     * Verifies whether the contact has the expected custom ringtone URI set.
     */
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
                val actual =
                    it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.CUSTOM_RINGTONE))
                if (actual != expectedUri) {
                    tracker.trackError(IllegalStateException("CUSTOM_RINGTONE mismatch. Expected: $expectedUri but found: $actual"))
                }
            } else {
                tracker.trackError(RuntimeException("Failed to query CUSTOM_RINGTONE for contact: $contactId"))
            }
        }
    }

    /**
     * Attempts to update the contact's CUSTOM_RINGTONE field.
     */
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

    /**
     * Tries to persist read permission to a URI (needed if using ACTION_OPEN_DOCUMENT picked URIs).
     */
    private fun tryPersistUriPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            tracker.trackError(RuntimeException("Cannot persist URI permission: $uri", e))
        }
    }
}
