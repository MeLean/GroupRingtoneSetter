package com.milen.grounpringtonesetter.backup

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.local.LocalLabelsStore
import com.milen.grounpringtonesetter.data.sources.ContactSource
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneManager
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneType
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.MediaStoreToneImporter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest

internal class BackupSnapshotBuilder(
    private val context: Context,
    private val contactsHelper: ContactsHelper,
    private val localLabelsStore: LocalLabelsStore,
    private val defaultToneManager: DeviceDefaultToneManager,
    private val toneImporter: MediaStoreToneImporter = MediaStoreToneImporter(),
) {
    suspend fun build(
        appVersionName: String,
        source: ContactSource,
        onProgress: (Int) -> Unit = {},
    ): BackupBuildResult {
        val coroutineContext = currentCoroutineContext()
        fun reportProgress(percent: Int) {
            coroutineContext.ensureActive()
            onProgress(percent.coerceIn(0, 100))
        }

        reportProgress(0)
        val createdAt = System.currentTimeMillis()
        val groups = loadGroups(source)
        reportProgress(20)

        val toneByKey = linkedMapOf<String, PendingTone>()
        val groupTones = buildGroupToneMap(
            groups = groups,
            toneByKey = toneByKey,
            reportProgress = { progress -> reportProgress(20 + ((progress * 50) / 100)) }
        )
        reportProgress(70)

        val defaultTones = buildDefaultTones(toneByKey)
        reportProgress(90)

        val snapshot = GrsBackupSnapshot(
            createdAtEpochMillis = createdAt,
            appVersionName = appVersionName,
            sourceLabel = source.displayLabel(context),
            groupTones = groupTones,
            defaultTones = defaultTones,
            tones = toneByKey.values.map { it.snapshot }
        )

        reportProgress(100)
        return BackupBuildResult(
            snapshot = snapshot,
            assets = toneByKey.values.map { it.asset }
        )
    }

    private suspend fun loadGroups(source: ContactSource): List<LabelItem> =
        when (source) {
            is ContactSource.CloudAccount -> contactsHelper.getAllLabelItemsForAccounts(source.account)
            ContactSource.OnDevice -> loadOnDeviceGroups()
        }

    private suspend fun loadOnDeviceGroups(): List<LabelItem> {
        val document = localLabelsStore.read()
        if (document.labels.isEmpty()) return emptyList()
        val contactsByLookupKey = contactsHelper.getContactsByLookupKeys(
            document.labels.flatMap { label -> label.members.map { it.lookupKey } }
        ).associateBy { it.lookupKey }

        return document.labels.map { label ->
            LabelItem(
                id = label.id,
                groupName = label.name,
                contacts = label.members.mapNotNull { member -> contactsByLookupKey[member.lookupKey] },
                ringtoneUriList = emptyList(),
                ringtoneFileName = ""
            )
        }
    }

    private fun buildGroupToneMap(
        groups: List<LabelItem>,
        toneByKey: MutableMap<String, PendingTone>,
        reportProgress: (Int) -> Unit,
    ): List<GrsGroupToneSnapshot> {
        val groupTones = linkedMapOf<String, GrsGroupToneSnapshot>()
        val totalGroups = groups.size.coerceAtLeast(1)
        groups.forEachIndexed { index, group ->
            val firstReadableRingtone = group.contacts
                .asSequence()
                .mapNotNull { contact -> contact.ringtoneUriStr?.takeIf { it.isNotBlank() } }
                .firstOrNull { ringtoneUri -> canReadTone(ringtoneUri) }
            if (firstReadableRingtone != null) {
                val category = GrsToneCategory.RINGTONE
                val tone = toneByKey.getOrPut(toneKey(firstReadableRingtone, category)) {
                    buildPendingTone(
                        uriStr = firstReadableRingtone,
                        category = category
                    )
                }
                groupTones.putIfAbsent(
                    group.groupName,
                    GrsGroupToneSnapshot(
                        groupName = group.groupName,
                        toneId = tone.snapshot.id
                    )
                )
            }
            reportProgress(((index + 1) * 100) / totalGroups)
        }
        return groupTones.values.toList()
    }

    private fun buildDefaultTones(
        toneByKey: MutableMap<String, PendingTone>,
    ): List<GrsDefaultToneSnapshot> =
        DeviceDefaultToneType.entries.mapNotNull { type ->
            val uri = defaultToneManager.getCurrentDefaultUri(type) ?: return@mapNotNull null
            val uriStr = uri.toString().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!toneImporter.canReadFromUri(context, uri)) return@mapNotNull null
            val category = type.toGrsToneCategory()
            val tone = toneByKey.getOrPut(toneKey(uriStr, category)) {
                buildPendingTone(
                    uriStr = uriStr,
                    category = category
                )
            }
            GrsDefaultToneSnapshot(
                type = type,
                toneId = tone.snapshot.id,
                displayName = defaultToneManager.getCurrentDisplayName(type)
            )
        }

    private fun canReadTone(uriStr: String): Boolean {
        val uri = runCatching { uriStr.toUri() }.getOrNull()
        return uri != null && toneImporter.canReadFromUri(context, uri)
    }

    private fun buildPendingTone(
        uriStr: String,
        category: GrsToneCategory,
    ): PendingTone {
        val uri = runCatching { uriStr.toUri() }.getOrNull()
            ?: throw GrsArchiveException("Invalid tone URI")
        val id = toneId(uriStr, category)
        val displayName = resolveDisplayName(uri)
            ?.takeIf { it.isNotBlank() }
            ?: uriStr
        val archivePath = "assets/$id-${displayName.safeArchiveName()}"
        val assetSize = resolveAssetSize(uri)
        val snapshot = GrsToneSnapshot(
            id = id,
            displayName = displayName,
            category = category,
            archivePath = archivePath,
            sizeBytes = assetSize
        )
        return PendingTone(
            snapshot = snapshot,
            asset = GrsToneAsset(
                toneId = id,
                archivePath = archivePath,
                sourceUri = uri,
                sizeBytes = assetSize
            )
        )
    }

    private fun resolveAssetSize(uri: Uri): Long? {
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it > 0L }
            }
        }.getOrNull()?.let { return it }

        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
            }
        }.getOrNull()
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val displayName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
        }.getOrNull()
        if (!displayName.isNullOrBlank()) return displayName

        return runCatching {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context)
        }.getOrNull()
    }

    private data class PendingTone(
        val snapshot: GrsToneSnapshot,
        val asset: GrsToneAsset,
    )
}

internal fun DeviceDefaultToneType.toGrsToneCategory(): GrsToneCategory =
    when (this) {
        DeviceDefaultToneType.RINGTONE -> GrsToneCategory.RINGTONE
        DeviceDefaultToneType.NOTIFICATION -> GrsToneCategory.NOTIFICATION
        DeviceDefaultToneType.ALARM -> GrsToneCategory.ALARM
    }

internal fun GrsToneCategory.toMediaStoreCategory(): MediaStoreToneImporter.ToneCategory =
    when (this) {
        GrsToneCategory.RINGTONE -> MediaStoreToneImporter.ToneCategory.RINGTONE
        GrsToneCategory.NOTIFICATION -> MediaStoreToneImporter.ToneCategory.NOTIFICATION
        GrsToneCategory.ALARM -> MediaStoreToneImporter.ToneCategory.ALARM
    }

private fun toneId(
    uriStr: String,
    category: GrsToneCategory,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("${category.name}|$uriStr".toByteArray())
    return digest.take(12).joinToString("") { byte -> "%02x".format(byte) }
}

private fun toneKey(
    uriStr: String,
    category: GrsToneCategory,
): String = "${category.name}|$uriStr"

private fun String.safeArchiveName(): String {
    val cleaned = replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
        .ifBlank { "tone" }
    return cleaned.take(80)
}
