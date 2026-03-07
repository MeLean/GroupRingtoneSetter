package com.milen.grounpringtonesetter.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.security.MessageDigest

internal class MediaStoreToneImporter {

    internal data class ImportToneResult(
        val uri: Uri,
        val wasNewlyCreated: Boolean,
    )

    internal enum class ToneCategory(
        val relativeDirectory: String,
        val isRingtone: Int,
        val isNotification: Int,
        val isAlarm: Int,
    ) {
        RINGTONE(
            relativeDirectory = Environment.DIRECTORY_RINGTONES,
            isRingtone = 1,
            isNotification = 0,
            isAlarm = 0
        ),
        NOTIFICATION(
            relativeDirectory = Environment.DIRECTORY_NOTIFICATIONS,
            isRingtone = 0,
            isNotification = 1,
            isAlarm = 0
        ),
        ALARM(
            relativeDirectory = Environment.DIRECTORY_ALARMS,
            isRingtone = 0,
            isNotification = 0,
            isAlarm = 1
        ),
    }

    fun importTone(
        context: Context,
        sourceUri: Uri,
        category: ToneCategory,
        desiredDisplayName: String? = null,
        reuseExistingByName: Boolean = true,
    ): Result<Uri> = importToneWithMetadata(
        context = context,
        sourceUri = sourceUri,
        category = category,
        desiredDisplayName = desiredDisplayName,
        reuseExistingByName = reuseExistingByName
    ).map { it.uri }

    fun importToneWithMetadata(
        context: Context,
        sourceUri: Uri,
        category: ToneCategory,
        desiredDisplayName: String? = null,
        reuseExistingByName: Boolean = true,
    ): Result<ImportToneResult> = runCatching {
        val displayName = desiredDisplayName ?: getNormalizedFileName(context, sourceUri)

        if (reuseExistingByName) {
            findExistingToneUri(context, displayName, category)?.let {
                return@runCatching ImportToneResult(uri = it, wasNewlyCreated = false)
            }
        }

        if (!hasLegacyMediaWritePermission(context)) {
            if (reuseExistingByName) {
                findExistingToneUri(context, displayName, category)?.let {
                    return@runCatching ImportToneResult(uri = it, wasNewlyCreated = false)
                }
            }
            throw SecurityException("MediaStore write permission missing")
        }

        val contentResolver = context.contentResolver
        val mediaCollection = mediaCollectionUri()
        val values = buildInsertValues(
            context = context,
            sourceUri = sourceUri,
            displayName = displayName,
            category = category
        )

        val destinationUri = try {
            contentResolver.insert(mediaCollection, values)
        } catch (illegalStateException: IllegalStateException) {
            val message = illegalStateException.message.orEmpty()
            if (!message.contains("Failed to build unique file", ignoreCase = true)) {
                throw illegalStateException
            }

            if (reuseExistingByName) {
                findExistingToneUri(context, displayName, category)?.let {
                    return@runCatching ImportToneResult(uri = it, wasNewlyCreated = false)
                }
            }
            val uniqueName = withUniqueSuffix(displayName)
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
            contentResolver.insert(mediaCollection, values)
        } ?: throw IllegalStateException("Failed to insert tone into MediaStore")

        val copySuccessful = runCatching {
            contentResolver.openInputStream(sourceUri)?.use { input ->
                contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                    input.copyTo(output)
                } ?: error("OpenOutputStream null")
            } ?: error("OpenInputStream null")
            true
        }.getOrElse {
            runCatching { contentResolver.delete(destinationUri, null, null) }
            false
        }

        if (!copySuccessful) {
            throw IllegalStateException("Failed to copy tone into MediaStore")
        }

        ImportToneResult(uri = destinationUri, wasNewlyCreated = true)
    }

    fun canReadFromUri(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)
    }

    fun findExistingToneUri(
        context: Context,
        displayName: String,
        category: ToneCategory,
    ): Uri? {
        val contentResolver = context.contentResolver
        val baseUri = mediaCollectionUri()
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val selectionParts = mutableListOf(
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            "${MediaStore.Audio.Media.IS_RINGTONE}=${category.isRingtone}",
            "${MediaStore.Audio.Media.IS_NOTIFICATION}=${category.isNotification}",
            "${MediaStore.Audio.Media.IS_ALARM}=${category.isAlarm}"
        )
        val selectionArgs = mutableListOf(displayName)

        if (Build.VERSION.SDK_INT >= 29) {
            selectionParts += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            selectionArgs += "%${category.relativeDirectory}%"
        }

        return runCatching {
            contentResolver.query(
                baseUri,
                projection,
                selectionParts.joinToString(" AND "),
                selectionArgs.toTypedArray(),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    ContentUris.withAppendedId(baseUri, id)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    fun getNormalizedFileName(context: Context, uri: Uri): String {
        val allowedExtensions = setOf("mp3", "wav", "ogg", "m4a", "aac")
        val actualMimeType = runCatching {
            context.contentResolver.getType(uri)
        }.getOrNull()

        val displayName = runCatching {
            if ("content".equals(uri.scheme, ignoreCase = true)) {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } else {
                null
            }
        }.getOrNull()

        val sanitizedBaseName = (
                displayName?.takeIf { it.isNotBlank() }
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: generateFallbackFileName(context, uri)
                ).replace(Regex("[^a-zA-Z0-9._-]"), "_")

        val dotIndex = sanitizedBaseName.lastIndexOf('.')
        val currentExtension = if (dotIndex > 0) {
            sanitizedBaseName.substring(dotIndex + 1).lowercase()
        } else {
            null
        }

        val hasAllowedExtension = currentExtension != null && currentExtension in allowedExtensions
        if (hasAllowedExtension && actualMimeType != null) {
            val mimeExtension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(actualMimeType)
                ?.lowercase()

            if (mimeExtension != null && mimeExtension != currentExtension && mimeExtension in allowedExtensions) {
                val baseWithoutExtension = sanitizedBaseName.substring(0, dotIndex)
                return "$baseWithoutExtension.$mimeExtension"
            }

            return sanitizedBaseName
        }

        val guessedExtension = runCatching {
            actualMimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        }.getOrNull()
            ?.lowercase()
            ?.takeIf { it in allowedExtensions }
            ?: "mp3"

        val baseWithoutExtension = if (dotIndex > 0) {
            sanitizedBaseName.substring(0, dotIndex)
        } else {
            sanitizedBaseName
        }
        return "$baseWithoutExtension.$guessedExtension"
    }

    fun getDeterministicFileName(context: Context, uri: Uri): String {
        val normalizedName = getNormalizedFileName(context, uri)
        val suffix = computeContentHashSuffix(context, uri) ?: computeUriSignatureSuffix(uri)

        val dotIndex = normalizedName.lastIndexOf('.')
        if (dotIndex <= 0) {
            return "${normalizedName}_$suffix"
        }

        val baseName = normalizedName.substring(0, dotIndex)
        val extension = normalizedName.substring(dotIndex + 1)
        return "${baseName}_$suffix.$extension"
    }

    private fun buildInsertValues(
        context: Context,
        sourceUri: Uri,
        displayName: String,
        category: ToneCategory,
    ): ContentValues {
        val contentResolver = context.contentResolver
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, contentResolver.getType(sourceUri) ?: guessMime(displayName))
            put(MediaStore.Audio.Media.IS_RINGTONE, category.isRingtone)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, category.isNotification)
            put(MediaStore.Audio.Media.IS_ALARM, category.isAlarm)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${category.relativeDirectory}/")
            }
        }
    }

    private fun hasLegacyMediaWritePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return true
        return context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun mediaCollectionUri(): Uri {
        return if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun withUniqueSuffix(displayName: String): String {
        val baseName = displayName.substringBeforeLast('.')
        val extension = displayName.substringAfterLast('.', "")
        val suffix = System.currentTimeMillis()
        return if (extension.isNotEmpty()) {
            "$baseName-$suffix.$extension"
        } else {
            "$baseName-$suffix"
        }
    }

    private fun generateFallbackFileName(context: Context, uri: Uri): String {
        val allowed = setOf("mp3", "wav", "ogg", "m4a", "aac")
        val guessedExt = runCatching {
            context.contentResolver.getType(uri)
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it)?.lowercase() }
                ?.takeIf { it in allowed }
        }.getOrNull() ?: "mp3"

        val signature = (uri.authority.orEmpty() + ":" + (uri.lastPathSegment ?: uri.toString()))
            .hashCode().toUInt().toString(16)
        return "tone_$signature.$guessedExt"
    }

    private fun guessMime(displayName: String): String {
        val lowerName = displayName.lowercase()
        return when {
            lowerName.endsWith(".mp3") -> "audio/mpeg"
            lowerName.endsWith(".wav") -> "audio/wav"
            lowerName.endsWith(".ogg") -> "audio/ogg"
            lowerName.endsWith(".m4a") -> "audio/mp4"
            lowerName.endsWith(".aac") -> "audio/aac"
            else -> "audio/mpeg"
        }
    }

    private fun computeContentHashSuffix(context: Context, uri: Uri): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            } ?: return null

            digest.digest()
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
                .take(8)
        }.getOrNull()
    }

    private fun computeUriSignatureSuffix(uri: Uri): String {
        val signatureSource = uri.toString()
        return signatureSource.hashCode().toUInt().toString(16).padStart(8, '0').takeLast(8)
    }
}
