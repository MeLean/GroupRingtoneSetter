package com.milen.grounpringtonesetter.ui.defaulttones

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.utils.MediaStoreToneImporter
import com.milen.grounpringtonesetter.utils.RingtoneFormatValidator

internal enum class DeviceDefaultToneType(
    val ringtoneManagerType: Int,
    val allowSilentSelection: Boolean,
    @param:StringRes val labelResId: Int,
) {
    RINGTONE(
        ringtoneManagerType = RingtoneManager.TYPE_RINGTONE,
        allowSilentSelection = false,
        labelResId = R.string.default_tone_ringtone_label
    ),
    NOTIFICATION(
        ringtoneManagerType = RingtoneManager.TYPE_NOTIFICATION,
        allowSilentSelection = true,
        labelResId = R.string.default_tone_notification_label
    ),
    ALARM(
        ringtoneManagerType = RingtoneManager.TYPE_ALARM,
        allowSilentSelection = true,
        labelResId = R.string.default_tone_alarm_label
    ),
}

internal data class ImportedCustomTone(
    val uri: Uri,
    val wasNewlyCreated: Boolean,
)

internal interface DeviceDefaultToneManager {
    fun canWriteSystemSettings(): Boolean
    fun createManageWriteSettingsIntent(): Intent
    fun createSoundSettingsIntent(): Intent
    fun getCurrentDefaultUri(type: DeviceDefaultToneType): Uri?
    fun getCurrentDisplayName(type: DeviceDefaultToneType): String
    fun validateToneSelection(type: DeviceDefaultToneType, uriOrNull: Uri?): Result<Unit>
    fun importCustomTone(type: DeviceDefaultToneType, sourceUri: Uri): Result<ImportedCustomTone>
    fun deleteImportedTone(uri: Uri)
    fun setDefaultTone(type: DeviceDefaultToneType, uriOrNull: Uri?): Result<Unit>
}

internal class AndroidDeviceDefaultToneManager(
    context: Context,
    private val toneImporter: MediaStoreToneImporter = MediaStoreToneImporter(),
) : DeviceDefaultToneManager {
    private val appContext = context.applicationContext

    override fun canWriteSystemSettings(): Boolean =
        Settings.System.canWrite(appContext)

    override fun createManageWriteSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${appContext.packageName}")
        }

    override fun createSoundSettingsIntent(): Intent =
        Intent(Settings.ACTION_SOUND_SETTINGS)

    override fun getCurrentDefaultUri(type: DeviceDefaultToneType): Uri? =
        RingtoneManager.getActualDefaultRingtoneUri(appContext, type.ringtoneManagerType)

    override fun getCurrentDisplayName(type: DeviceDefaultToneType): String {
        val currentUri = getCurrentDefaultUri(type)
            ?: return if (type.allowSilentSelection) {
                appContext.getString(R.string.default_tone_silent)
            } else {
                appContext.getString(R.string.default_tone_unknown)
            }

        val title = runCatching {
            RingtoneManager.getRingtone(appContext, currentUri)?.getTitle(appContext)
        }.getOrNull()

        return title?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.default_tone_unknown)
    }

    override fun validateToneSelection(
        type: DeviceDefaultToneType,
        uriOrNull: Uri?
    ): Result<Unit> = runCatching {
        if (type != DeviceDefaultToneType.NOTIFICATION || uriOrNull == null) {
            return@runCatching
        }

        val durationMs = readDurationMs(uriOrNull)
        val durationFailure = NotificationToneDurationPolicy.getFailureReason(durationMs)
        if (durationFailure != null) {
            throw DefaultToneImportException(durationFailure)
        }
    }

    override fun importCustomTone(
        type: DeviceDefaultToneType,
        sourceUri: Uri
    ): Result<ImportedCustomTone> = runCatching {
        val formatErrorResId = RingtoneFormatValidator.validateRingtoneFormat(appContext, sourceUri)
        if (formatErrorResId != null) {
            throw DefaultToneImportException(DefaultToneImportFailureReason.INVALID_FORMAT)
        }

        validateToneSelection(type, sourceUri).getOrThrow()

        val category = when (type) {
            DeviceDefaultToneType.RINGTONE -> MediaStoreToneImporter.ToneCategory.RINGTONE
            DeviceDefaultToneType.NOTIFICATION -> MediaStoreToneImporter.ToneCategory.NOTIFICATION
            DeviceDefaultToneType.ALARM -> MediaStoreToneImporter.ToneCategory.ALARM
        }
        val deterministicDisplayName = toneImporter.getDeterministicFileName(appContext, sourceUri)

        val importedTone = toneImporter.importToneWithMetadata(
            context = appContext,
            sourceUri = sourceUri,
            category = category,
            desiredDisplayName = deterministicDisplayName,
            reuseExistingByName = true
        ).getOrElse { importError ->
            if (isLegacyStoragePermissionError(importError)) {
                throw DefaultToneImportException(
                    DefaultToneImportFailureReason.LEGACY_STORAGE_PERMISSION_REQUIRED,
                    importError
                )
            }
            throw DefaultToneImportException(DefaultToneImportFailureReason.IMPORT_FAILED, importError)
        }

        ImportedCustomTone(
            uri = importedTone.uri,
            wasNewlyCreated = importedTone.wasNewlyCreated
        )
    }

    override fun deleteImportedTone(uri: Uri) {
        runCatching { appContext.contentResolver.delete(uri, null, null) }
    }

    override fun setDefaultTone(type: DeviceDefaultToneType, uriOrNull: Uri?): Result<Unit> =
        runCatching {
            RingtoneManager.setActualDefaultRingtoneUri(
                appContext,
                type.ringtoneManagerType,
                uriOrNull
            )
        }

    private fun readDurationMs(sourceUri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, sourceUri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun isLegacyStoragePermissionError(error: Throwable): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        if (error !is SecurityException) return false
        return appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
    }
}
