package com.milen.grounpringtonesetter.utils

import android.content.Context
import android.net.Uri
import com.milen.grounpringtonesetter.R

internal object RingtoneFormatValidator {
    val SUPPORTED_MIME_TYPES = setOf(
        "audio/mpeg",      // MP3
        "audio/mp4",       // M4A (audio-only MP4)
        "audio/ogg",       // OGG
        "audio/wav",       // WAV
        "audio/x-wav",     // WAV variant
        "audio/aac",       // AAC
        "audio/aacp"       // AAC variant
    )

    val SUPPORTED_EXTENSIONS = setOf("mp3", "m4a", "ogg", "wav", "aac")
    
    /**
     * Returns the MIME type filter to use for the file picker.
     * Uses "audio/mpeg" (MP3) as the primary filter since it's the most common format.
     * The validation will still accept other supported formats after selection.
     */
    fun getMimeTypeFilter(): String = "audio/mpeg"

    /**
     * Validates if a file can be used as a ringtone on Android.
     * Only files with supported formats (MP3, M4A, OGG, WAV, AAC) are allowed.
     * 
     * @return null if valid, error message resource ID if invalid
     */
    fun validateRingtoneFormat(context: Context, uri: Uri): Int? {
        val mimeType = runCatching {
            context.contentResolver.getType(uri)
        }.getOrNull()

        val fileName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use {
                if (it.moveToFirst()) {
                    it.getString(0)
                } else null
            }
        }.getOrNull()

        // Reject video files
        if (mimeType?.startsWith("video/") == true) {
            return R.string.ringtone_format_not_supported
        }

        // Reject audio/mp4 with .mp4 extension (likely a video file, not M4A)
        if (mimeType == "audio/mp4" && fileName != null) {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext == "mp4") {
                return R.string.ringtone_format_not_supported
            }
            // audio/mp4 with .m4a extension is valid (M4A is supported)
        }

        // Check if MIME type is supported
        if (mimeType != null) {
            if (mimeType in SUPPORTED_MIME_TYPES) {
                return null // Valid
            }
            // If MIME type is not in supported list, check extension as fallback
            if (fileName != null) {
                val ext = fileName.substringAfterLast('.', "").lowercase()
                if (ext in SUPPORTED_EXTENSIONS) {
                    return null // Valid based on extension
                }
            }
        } else {
            // No MIME type available, check extension only
            if (fileName != null) {
                val ext = fileName.substringAfterLast('.', "").lowercase()
                if (ext in SUPPORTED_EXTENSIONS) {
                    return null // Valid based on extension
                }
            }
        }

        // Not a supported format - use generic error message
        return R.string.ringtone_format_not_supported
    }
}

