package com.milen.grounpringtonesetter.ui.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream
import java.io.OutputStream

internal data class BackupDocumentMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
    val mimeType: String?,
)

/** Stream and metadata boundary for Storage Access Framework documents. */
internal interface BackupDocumentGateway {
    fun openInputStream(uri: Uri): InputStream?

    fun openOutputStream(uri: Uri): OutputStream?

    fun metadata(uri: Uri): BackupDocumentMetadata
}

internal class ContentResolverBackupDocumentGateway(
    private val contentResolver: ContentResolver,
) : BackupDocumentGateway {
    override fun openInputStream(uri: Uri): InputStream? = contentResolver.openInputStream(uri)

    override fun openOutputStream(uri: Uri): OutputStream? = contentResolver.openOutputStream(uri)

    override fun metadata(uri: Uri): BackupDocumentMetadata {
        val values = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val displayName = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString)
                val sizeBytes = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getLong)
                displayName to sizeBytes
            }
        }.getOrNull()

        return BackupDocumentMetadata(
            displayName = values?.first,
            sizeBytes = values?.second,
            mimeType = runCatching { contentResolver.getType(uri) }.getOrNull(),
        )
    }
}
