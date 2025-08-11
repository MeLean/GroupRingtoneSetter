package com.milen.grounpringtonesetter.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
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

                // If already in MediaStore, reuse
                findExistingRingtoneUri(context, fileName)?.let { return@withContext it }

                val src = try {
                    context.contentResolver.openInputStream(uri)
                } catch (e: Exception) {
                    null
                }
                if (src == null) {
                    tracker.trackError(RuntimeException("Source not readable for copy: $uri"))
                    return@withContext null
                }

                val mime = context.contentResolver.getType(uri) ?: "audio/mpeg"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES)
                    }
                    put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                    put(MediaStore.Audio.Media.IS_MUSIC, 0)
                }
                val collection = if (Build.VERSION.SDK_INT >= 29) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

                val dest = context.contentResolver.insert(collection, values) ?: run {
                    tracker.trackError(RuntimeException("Insert failed for $fileName"))
                    src.close()
                    return@withContext null
                }

                try {
                    context.contentResolver.openOutputStream(dest, "w")?.use { out ->
                        src.use { input -> input.copyTo(out) }
                    } ?: run {
                        // No output → cleanup
                        context.contentResolver.delete(dest, null, null)
                        tracker.trackError(RuntimeException("OpenOutputStream failed for $dest"))
                        return@withContext null
                    }
                    return@withContext dest
                } catch (t: Throwable) {
                    // Copy failed → cleanup
                    try {
                        context.contentResolver.delete(dest, null, null)
                    } catch (_: Throwable) {
                    }
                    tracker.trackError(RuntimeException("Copy failed to $dest: ${t.message}", t))
                    return@withContext null
                }
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
        val allowedExtensions = setOf("mp3", "wav", "ogg", "m4a", "aac")

        return try {
            var name: String? = null

            if ("content".equals(uri.scheme, ignoreCase = true)) {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )
                    ?.use { c -> if (c.moveToFirst()) name = c.getString(0) }
            }

            val base = (name?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: generateFallbackFileName(context, uri))
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")

            appendOrGuessAudioExtension(context, base, uri, allowedExtensions)
        } catch (e: SecurityException) {
            tracker.trackError(e)
            val fallback = generateFallbackFileName(context, uri)
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            appendOrGuessAudioExtension(
                context,
                fallback,
                uri,
                setOf("mp3", "wav", "ogg", "m4a", "aac")
            )
        }
    }

    private fun appendOrGuessAudioExtension(
        context: Context,
        fileName: String,
        uri: Uri,
        allowedExtensions: Set<String>,
    ): String {
        val dot = fileName.lastIndexOf('.')
        val hasValidExt = dot > 0 && fileName.substring(dot + 1).lowercase() in allowedExtensions
        if (hasValidExt) return fileName

        val guessedExt = try {
            context.contentResolver.getType(uri)?.let { mime ->
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                if (ext == null) {
                    tracker.trackEvent(
                        "audio_extension_guess_failed",
                        mapOf("mime" to mime, "uri" to uri.toString())
                    )
                } else {
                    tracker.trackEvent(
                        "audio_extension_guessed",
                        mapOf("mime" to mime, "ext" to ext, "uri" to uri.toString())
                    )
                }
                ext
            } ?: run {
                tracker.trackEvent("audio_mime_missing", mapOf("uri" to uri.toString()))
                null
            }
        } catch (e: Throwable) {
            tracker.trackError(RuntimeException("Error guessing extension for URI: $uri", e))
            null
        }

        val ext = guessedExt?.lowercase().takeUnless { it.isNullOrBlank() } ?: "mp3"
        return "$fileName.$ext"
    }

    private fun generateFallbackFileName(context: Context, uri: Uri): String {
        val guessedExt = context.contentResolver.getType(uri)?.let { mime ->
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        }

        val uuid = uri.lastPathSegment
            ?.hashCode()
            ?.toUInt()
            ?.toString(16)
            ?: System.currentTimeMillis().toString()

        val extension = guessedExt ?: "mp3"
        return "ringtone_$uuid.$extension"
    }

    /**
     * Tries to find an existing ringtone in MediaStore by file name.
     */
    private fun findExistingRingtoneUri(context: Context, fileName: String): Uri? {
        val resolver = context.contentResolver
        val baseUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME
        )

        return try {
            val (selection, args) =
                if (Build.VERSION.SDK_INT >= 29) {
                    "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} = ?" to
                            arrayOf(fileName, "${Environment.DIRECTORY_RINGTONES}/")
                } else {
                    "${MediaStore.Audio.Media.DISPLAY_NAME} = ?" to arrayOf(fileName)
                }

            resolver.query(baseUri, projection, selection, args, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(idCol)
                    return ContentUris.withAppendedId(baseUri, id)
                }
            }

            tracker.trackEvent(
                "ringtone_not_found_in_mediastore",
                mapOf("fileName" to fileName, "sdk" to Build.VERSION.SDK_INT)
            )
            null
        } catch (e: Exception) {
            tracker.trackError(
                RuntimeException(
                    "Error querying MediaStore for existing ringtone: $fileName (${e.message})",
                    e
                )
            )
            null
        }
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
            if (!DocumentsContract.isDocumentUri(context, uri)) {
                tracker.trackError(IllegalStateException("Non-persistable URI skipped: $uri"))
                return
            }
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            tracker.trackError(e)
        }
    }
}
