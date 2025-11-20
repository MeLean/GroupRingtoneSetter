package com.milen.grounpringtonesetter.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import kotlinx.coroutines.withContext

internal class ContactRingtoneUpdateHelper(
    private val tracker: Tracker,
    private val preferenceHelper: EncryptedPreferencesHelper,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider,
) {

    suspend fun scanAndUpdate(context: Context, ringtoneStr: String, contactId: Long) {
        val src = ringtoneStr.toUri()
        if (ringtoneStr.isBlank() || src == Uri.EMPTY) {
            tracker.trackError(IllegalArgumentException("Invalid ringtone URI: $ringtoneStr"))
            return
        }

        val finalUri = withContext(dispatcherProvider.io) {
            preparePlayableRingtoneUri(context, src)
        }
        if (finalUri == null) {
            tracker.trackEvent("ringtone_prepare_failed", scrubUriForTelemetry(src))
            return
        }

        val displayName = withContext(dispatcherProvider.io) {
            getNormalizedFileName(context, finalUri)
        }
        withContext(dispatcherProvider.io) {
            preferenceHelper.saveStringAsync(finalUri.toString(), displayName)
        }

        val updated = withContext(dispatcherProvider.io) {
            tryUpdateCustomRingtone(context, contactId, finalUri.toString())
        }
        if (!updated) {
            tracker.trackEvent(
                "contact_custom_ringtone_not_updated",
                mapOf("contactId" to contactId.toString()) + scrubUriForTelemetry(finalUri)
            )
            return
        }

        withContext(dispatcherProvider.io) {
            softVerifyCustomRingtone(context, contactId, finalUri.toString())
        }
    }

    private suspend fun preparePlayableRingtoneUri(context: Context, source: Uri): Uri? =
        withContext(dispatcherProvider.io) {
            val meta = scrubUriForTelemetry(source)
            val desiredName = getNormalizedFileName(context, source)

            findExistingRingtoneUri(context, desiredName)?.let {
                tracker.trackEvent("ringtone_reuse_in_mediastore", meta + ("name" to desiredName))
                return@withContext it
            }

            if (!canReadFromUri(context, source)) {
                tracker.trackEvent("preflight_unreadable_uri", meta)
                return@withContext null
            }

            copyRingtoneToScopedMedia(context, source, desiredName)?.let {
                tracker.trackEvent("ringtone_copied_to_mediastore", meta + ("name" to desiredName))
                return@withContext it
            }

            if (DocumentsContract.isDocumentUri(context, source)) {
                tryPersistUriPermission(context, source)
                tracker.trackEvent("ringtone_using_persisted_saf", meta)
                return@withContext source
            }

            tracker.trackEvent("non_persistable_vendor_uri_rejected", meta)
            null
        }

    private fun canReadFromUri(context: Context, uri: Uri): Boolean {
        if (!"content".equals(uri.scheme, ignoreCase = true)) {
            tracker.trackEvent("uri_not_content_scheme", scrubUriForTelemetry(uri))
            return false
        }
        val auth = uri.authority.orEmpty()
        val pm = context.packageManager
        if (pm.resolveContentProvider(auth, PackageManager.GET_META_DATA) == null) {
            tracker.trackEvent("content_provider_missing", scrubUriForTelemetry(uri))
            return false
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        }.onFailure {
            tracker.trackEvent(
                "content_provider_unreadable",
                scrubUriForTelemetry(uri) + ("reason" to (it.message ?: it::class.java.simpleName))
            )
        }.getOrDefault(false)
    }

    private suspend fun copyRingtoneToScopedMedia(
        context: Context,
        src: Uri,
        displayName: String,
    ): Uri? = withContext(dispatcherProvider.io) {
        val cr = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // If a ringtone with this name already exists, reuse it
        findExistingRingtoneUri(context, displayName)?.let {
            tracker.trackEvent("ringtone_copy_preexisting_reused", mapOf("name" to displayName))
            return@withContext it
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, cr.getType(src) ?: guessMime(displayName))
            put(MediaStore.Audio.Media.IS_RINGTONE, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            if (Build.VERSION.SDK_INT >= 29) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_RINGTONES + "/"
                )
            }
        }

        val dest = try {
            cr.insert(collection, values)
        } catch (e: IllegalStateException) {
            val errorMsg = e.message ?: e::class.java.simpleName
            tracker.trackEvent(
                "mediastore_insert_illegal_state",
                mapOf("name" to displayName, "reason" to errorMsg)
            )
            if (errorMsg.contains("Failed to build unique file", ignoreCase = true)) {
                val existing = findExistingRingtoneUri(context, displayName)
                if (existing != null) {
                    tracker.trackEvent(
                        "mediastore_insert_duplicate_found",
                        mapOf("name" to displayName)
                    )
                    return@withContext existing
                }
                val baseName = displayName.substringBeforeLast('.')
                val ext = displayName.substringAfterLast('.', "")
                val timestamp = System.currentTimeMillis()
                val uniqueName = if (ext.isNotEmpty()) "$baseName-$timestamp.$ext" else "$baseName-$timestamp"
                tracker.trackEvent(
                    "mediastore_insert_retry_unique_name",
                    mapOf("original" to displayName, "unique" to uniqueName)
                )
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
                runCatching { cr.insert(collection, values) }.getOrNull()
            } else {
                findExistingRingtoneUri(context, displayName)
            }
        } ?: run {
            tracker.trackEvent("mediastore_insert_failed", mapOf("name" to displayName))
            return@withContext null
        }

        val ok = runCatching {
            cr.openInputStream(src)?.use { input ->
                cr.openOutputStream(dest, "w")?.use { out ->
                    input.copyTo(out)
                } ?: error("OpenOutputStream null")
            } ?: error("OpenInputStream null")
            true
        }.onFailure {
            runCatching { cr.delete(dest, null, null) }
            tracker.trackEvent(
                "ringtone_copy_failed",
                mapOf("name" to displayName, "reason" to (it.message ?: it::class.java.simpleName))
            )
        }.getOrDefault(false)

        if (ok) dest else null
    }

    private fun findExistingRingtoneUri(context: Context, fileName: String): Uri? {
        val cr = context.contentResolver
        val base = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val (sel, args) = if (Build.VERSION.SDK_INT >= 29) {
            ("${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.Audio.Media.IS_RINGTONE}=1 AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?") to
                    arrayOf(fileName, "%${Environment.DIRECTORY_RINGTONES}%")
        } else {
            ("${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.Audio.Media.IS_RINGTONE}=1") to
                    arrayOf(fileName)
        }
        return runCatching {
            cr.query(base, projection, sel, args, null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    ContentUris.withAppendedId(base, id)
                } else null
            }
        }.getOrNull()
    }

    private fun tryUpdateCustomRingtone(
        context: Context,
        contactId: Long,
        uriStr: String,
    ): Boolean {
        val contactUri =
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val values = ContentValues().apply {
            put(ContactsContract.Contacts.CUSTOM_RINGTONE, uriStr)
        }
        return try {
            val rows = context.contentResolver.update(contactUri, values, null, null)
            if (rows <= 0) {
                tracker.trackEvent(
                    "contact_read_only_or_not_found",
                    mapOf("contactId" to contactId.toString())
                )
                false
            } else true
        } catch (se: SecurityException) {
            tracker.trackError(se)
            false
        }
    }

    private fun softVerifyCustomRingtone(context: Context, contactId: Long, expected: String) {
        val uri =
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts.CUSTOM_RINGTONE),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val actual = c.getString(
                        c.getColumnIndexOrThrow(
                            ContactsContract.Contacts.CUSTOM_RINGTONE
                        )
                    )
                    if (actual != expected) {
                        tracker.trackEvent(
                            "custom_ringtone_mismatch",
                            mapOf(
                                "expected" to expected,
                                "actual" to (actual ?: "null")
                            )
                        )
                    }
                } else {
                    tracker.trackEvent(
                        "custom_ringtone_verify_no_row",
                        mapOf("contactId" to contactId.toString())
                    )
                }
            }
        }.onFailure {
            tracker.trackEvent(
                "custom_ringtone_verify_failed",
                mapOf("reason" to (it.message ?: it::class.java.simpleName))
            )
        }
    }

    private fun tryPersistUriPermission(context: Context, uri: Uri) {
        try {
            if (!DocumentsContract.isDocumentUri(context, uri)) {
                tracker.trackEvent("non_persistable_uri_skipped", scrubUriForTelemetry(uri))
                return
            }
            val persistedUris = context.contentResolver.persistedUriPermissions
            val isAlreadyPersisted = persistedUris.any { it.uri == uri }
            if (isAlreadyPersisted) {
                tracker.trackEvent("uri_already_persisted", scrubUriForTelemetry(uri))
                return
            }
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            tracker.trackEvent("uri_persisted_success", scrubUriForTelemetry(uri))
        } catch (e: SecurityException) {
            tracker.trackEvent(
                "uri_persist_failed_not_granted",
                scrubUriForTelemetry(uri) + ("reason" to (e.message ?: e::class.java.simpleName))
            )
        }
    }

    private fun getNormalizedFileName(context: Context, uri: Uri): String {
        // Note: "mp4" removed - video MP4 files are not supported as ringtones
        // M4A files use "audio/mp4" MIME type but have .m4a extension
        val allowed = setOf("mp3", "wav", "ogg", "m4a", "aac")
        val actualMimeType = runCatching {
            context.contentResolver.getType(uri)
        }.getOrNull()
        
        val meta = runCatching {
            if ("content".equals(uri.scheme, ignoreCase = true)) {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            } else null
        }.getOrNull()

        val base = (
                meta?.takeIf { it.isNotBlank() }
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: generateFallbackFileName(context, uri)
                ).replace(Regex("[^a-zA-Z0-9._-]"), "_")

        val dot = base.lastIndexOf('.')
        val currentExt = if (dot > 0) base.substring(dot + 1).lowercase() else null
        val hasAllowedExt = currentExt != null && currentExt in allowed
        
        if (hasAllowedExt && actualMimeType != null) {
            val mimeExt = MimeTypeMap.getSingleton().getExtensionFromMimeType(actualMimeType)?.lowercase()
            if (mimeExt != null && mimeExt != currentExt && mimeExt in allowed) {
                val baseWithoutExt = base.substring(0, dot)
                tracker.trackEvent(
                    "ringtone_extension_corrected",
                    mapOf(
                        "original_ext" to currentExt,
                        "corrected_ext" to mimeExt,
                        "mime_type" to actualMimeType
                    )
                )
                return "$baseWithoutExt.$mimeExt"
            }
            return base
        }

        val guessed = runCatching {
            actualMimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        }.getOrNull()
        val ext = (guessed?.lowercase()).takeUnless { it.isNullOrBlank() || it !in allowed } ?: "mp3"
        val baseWithoutExt = if (dot > 0) base.substring(0, dot) else base
        return "$baseWithoutExt.$ext"
    }

    private fun generateFallbackFileName(context: Context, uri: Uri): String {
        val allowed = setOf("mp3", "wav", "ogg", "m4a", "aac", "mp4")
        val guessedExt = runCatching {
            context.contentResolver.getType(uri)
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it)?.lowercase() }
                ?.takeIf { it in allowed }
        }.getOrNull() ?: "mp3"
        val sig = (uri.authority.orEmpty() + ":" + (uri.lastPathSegment ?: uri.toString()))
            .hashCode().toUInt().toString(16)
        return "ringtone_$sig.$guessedExt"
    }

    private fun guessMime(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".mp3") -> "audio/mpeg"
            n.endsWith(".wav") -> "audio/wav"
            n.endsWith(".ogg") -> "audio/ogg"
            n.endsWith(".m4a") -> "audio/mp4"  // M4A uses audio/mp4 MIME type
            n.endsWith(".aac") -> "audio/aac"
            else -> "audio/mpeg"
        }
    }

    private fun scrubUriForTelemetry(uri: Uri): Map<String, String> {
        val auth = uri.authority ?: ""
        val last = uri.lastPathSegment ?: ""
        val sig = ("$auth|$last").hashCode().toUInt().toString(16)
        return mapOf(
            "scheme" to (uri.scheme ?: ""),
            "authority" to auth,
            "uri_sig" to sig
        )
    }
}
