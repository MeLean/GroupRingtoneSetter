package com.milen.grounpringtonesetter.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import kotlinx.coroutines.withContext

internal data class ContactRingtoneWriteResult(
    val contactId: Long,
    val appliedUri: String?,
) {
    val isSuccessful: Boolean
        get() = !appliedUri.isNullOrBlank()
}

internal class ContactRingtoneUpdateHelper(
    private val tracker: Tracker,
    private val preferenceHelper: EncryptedPreferencesHelper,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider,
    private val toneImporter: MediaStoreToneImporter = MediaStoreToneImporter(),
) {

    suspend fun scanAndUpdate(
        context: Context,
        ringtoneStr: String,
        contactId: Long,
    ): ContactRingtoneWriteResult {
        val src = ringtoneStr.toUri()
        if (ringtoneStr.isBlank() || src == Uri.EMPTY) {
            tracker.trackError(IllegalArgumentException("Invalid ringtone URI"))
            tracker.trackEvent(
                "invalid_ringtone_uri",
                mapOf("uri_sig" to ringtoneStringSignature(ringtoneStr))
            )
            return ContactRingtoneWriteResult(contactId = contactId, appliedUri = null)
        }

        val finalUri = withContext(dispatcherProvider.io) {
            preparePlayableRingtoneUri(context, src)
        }
        if (finalUri == null) {
            tracker.trackEvent("ringtone_prepare_failed", scrubUriForTelemetry(src))
            return ContactRingtoneWriteResult(contactId = contactId, appliedUri = null)
        }
        val finalUriString = finalUri.toString()

        val displayName = withContext(dispatcherProvider.io) {
            toneImporter.getNormalizedFileName(context, finalUri)
        }
        withContext(dispatcherProvider.io) {
            preferenceHelper.saveStringAsync(finalUriString, displayName)
        }

        val updated = withContext(dispatcherProvider.io) {
            tryUpdateCustomRingtone(context, contactId, finalUriString)
        }
        if (!updated) {
            tracker.trackEvent(
                "contact_custom_ringtone_not_updated",
                mapOf("contactId" to contactId.toString()) + scrubUriForTelemetry(finalUri)
            )
            return ContactRingtoneWriteResult(contactId = contactId, appliedUri = null)
        }

        val persistedUri = withContext(dispatcherProvider.io) {
            readBackCustomRingtone(context, contactId, finalUriString)
        }
        if (persistedUri.isNullOrBlank()) {
            return ContactRingtoneWriteResult(contactId = contactId, appliedUri = null)
        }
        if (persistedUri != finalUriString) {
            withContext(dispatcherProvider.io) {
                preferenceHelper.saveStringAsync(persistedUri, displayName)
            }
        }

        return ContactRingtoneWriteResult(contactId = contactId, appliedUri = persistedUri)
    }

    private suspend fun preparePlayableRingtoneUri(context: Context, source: Uri): Uri? =
        withContext(dispatcherProvider.io) {
            val meta = scrubUriForTelemetry(source)
            val desiredName = toneImporter.getNormalizedFileName(context, source)

            toneImporter.findExistingToneUri(
                context = context,
                displayName = desiredName,
                category = MediaStoreToneImporter.ToneCategory.RINGTONE
            )?.let {
                tracker.trackEvent("ringtone_reuse_in_mediastore", meta + ("name" to desiredName))
                return@withContext it
            }

            if (!toneImporter.canReadFromUri(context, source)) {
                tracker.trackEvent("preflight_unreadable_uri", meta)
                return@withContext null
            }

            toneImporter.importTone(
                context = context,
                sourceUri = source,
                category = MediaStoreToneImporter.ToneCategory.RINGTONE,
                desiredDisplayName = desiredName
            ).onSuccess { importedUri ->
                tracker.trackEvent("ringtone_copied_to_mediastore", meta + ("name" to desiredName))
                return@withContext importedUri
            }.onFailure { error ->
                tracker.trackEvent(
                    "ringtone_copy_failed",
                    meta + ("reason" to (error.message ?: error::class.java.simpleName))
                )
            }

            if (DocumentsContract.isDocumentUri(context, source)) {
                tryPersistUriPermission(context, source)
                tracker.trackEvent("ringtone_using_persisted_saf", meta)
                return@withContext source
            }

            tracker.trackEvent("non_persistable_vendor_uri_rejected", meta)
            null
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
        val rawContactValues = ContentValues().apply {
            put(ContactsContract.RawContacts.CUSTOM_RINGTONE, uriStr)
        }
        return try {
            val contactRowsUpdated = context.contentResolver.update(contactUri, values, null, null)
            val rawContactRowsUpdated = runCatching {
                context.contentResolver.update(
                    ContactsContract.RawContacts.CONTENT_URI,
                    rawContactValues,
                    "${ContactsContract.RawContacts.CONTACT_ID} = ? AND " +
                            "${ContactsContract.RawContacts.DELETED} = 0",
                    arrayOf(contactId.toString())
                )
            }.getOrElse { error ->
                tracker.trackEvent(
                    "contact_custom_ringtone_raw_update_failed",
                    mapOf(
                        "contactId" to contactId.toString(),
                        "reason" to (error.message ?: error::class.java.simpleName)
                    )
                )
                0
            }
            if (contactRowsUpdated <= 0) {
                tracker.trackEvent(
                    "contact_custom_ringtone_contact_row_not_updated",
                    mapOf("contactId" to contactId.toString())
                )
            }
            if (rawContactRowsUpdated <= 0) {
                tracker.trackEvent(
                    "contact_custom_ringtone_raw_rows_not_updated",
                    mapOf("contactId" to contactId.toString())
                )
            }
            if (contactRowsUpdated <= 0 && rawContactRowsUpdated <= 0) {
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

    private fun readBackCustomRingtone(
        context: Context,
        contactId: Long,
        expected: String,
    ): String? {
        val uri =
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        return runCatching {
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
                                "expected_sig" to ringtoneStringSignature(expected),
                                "actual_sig" to ringtoneStringSignature(actual)
                            )
                        )
                    }
                    actual?.takeIf { it.isNotBlank() }
                } else {
                    tracker.trackEvent(
                        "custom_ringtone_verify_no_row",
                        mapOf("contactId" to contactId.toString())
                    )
                    null
                }
            }
        }.onFailure {
            tracker.trackEvent(
                "custom_ringtone_verify_failed",
                mapOf("reason" to (it.message ?: it::class.java.simpleName))
            )
        }.getOrNull()
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
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            tracker.trackEvent("uri_persisted_success", scrubUriForTelemetry(uri))
        } catch (e: SecurityException) {
            tracker.trackEvent(
                "uri_persist_failed_not_granted",
                scrubUriForTelemetry(uri) + ("reason" to (e.message ?: e::class.java.simpleName))
            )
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

    private fun ringtoneStringSignature(value: String?): String {
        if (value.isNullOrBlank()) return "empty"
        return value.hashCode().toUInt().toString(16)
    }
}
