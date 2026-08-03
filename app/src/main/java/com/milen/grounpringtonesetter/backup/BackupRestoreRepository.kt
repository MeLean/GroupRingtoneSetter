package com.milen.grounpringtonesetter.backup

import android.content.Intent
import com.milen.grounpringtonesetter.BuildConfig
import com.milen.grounpringtonesetter.data.sources.ContactSource
import com.milen.grounpringtonesetter.data.sources.ContactSourceRepository
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneManager
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.Telemetry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class BackupRestoreSourceSelectionException : Exception()
internal class BackupRestoreContactsPermissionException : Exception()

internal class BackupRestoreRepository(
    private val snapshotBuilder: BackupSnapshotBuilder,
    private val archiveWriter: GrsArchiveWriter,
    private val archiveReader: GrsArchiveReader,
    private val restorePlanner: RestorePlanner,
    private val restoreExecutor: RestoreExecutor,
    private val contactsHelper: ContactsHelper,
    private val sourceRepository: ContactSourceRepository,
    private val defaultToneManager: DeviceDefaultToneManager,
    private val tracker: Telemetry,
) : BackupRestoreGateway {
    override suspend fun exportBackup(
        output: OutputStream,
        onProgress: (Int) -> Unit,
    ): BackupExportResult {
        val coroutineContext = currentCoroutineContext()
        requireReadContactsPermission()
        onProgress(0)
        tracker.trackEvent("grs_backup_export_started")
        val selectedSource = currentSourceOrThrow()
        val buildResult = snapshotBuilder.build(
            appVersionName = BuildConfig.VERSION_NAME,
            source = selectedSource,
            onProgress = { snapshotProgress ->
                onProgress(SNAPSHOT_START_PROGRESS +
                        ((snapshotProgress.coerceIn(0, 100) * SNAPSHOT_PROGRESS_RANGE) / 100))
            }
        )
        coroutineContext.ensureActive()
        val writtenAssets = archiveWriter.write(
            output = output,
            request = GrsArchiveWriteRequest(
                snapshot = buildResult.snapshot,
                assets = buildResult.assets
            ),
            onProgress = { archiveProgress ->
                onProgress(ARCHIVE_START_PROGRESS +
                        ((archiveProgress.coerceIn(0, 100) * ARCHIVE_PROGRESS_RANGE) / 100))
            },
            isCancelled = { !coroutineContext.isActive }
        )
        val snapshot = buildResult.snapshot
        onProgress(100)
        tracker.trackEvent(
            "grs_backup_export_finished",
            mapOf(
                "groupToneCount" to snapshot.groupTones.size.toString(),
                "defaultToneCount" to snapshot.defaultTones.size.toString(),
                "assetCount" to writtenAssets.toString()
            )
        )
        return BackupExportResult(
            groupToneCount = snapshot.groupTones.size,
            defaultToneCount = snapshot.defaultTones.size,
            assetCount = writtenAssets
        )
    }

    override suspend fun readAndPlanRestore(
        input: InputStream,
        onProgress: (Int) -> Unit,
    ): PendingRestore {
        val coroutineContext = currentCoroutineContext()
        onProgress(0)
        tracker.trackEvent("grs_restore_read_started")
        currentSourceOrThrow()
        val snapshot = archiveReader.readSnapshot(
            input = input,
            onProgress = { readProgress ->
                onProgress(RESTORE_READ_START_PROGRESS +
                        ((readProgress.coerceIn(0, 100) * RESTORE_READ_PROGRESS_RANGE) / 100))
            },
            isCancelled = { !coroutineContext.isActive }
        )
        coroutineContext.ensureActive()
        val plan = restorePlanner.plan(snapshot)
        onProgress(100)
        tracker.trackEvent(
            "grs_restore_plan_created",
            mapOf(
                "groups" to plan.groupTones.size.toString(),
                "defaults" to plan.defaultToneCount.toString()
            )
        )
        return PendingRestore(
            archiveContent = BackupArchiveContent(
                snapshot = snapshot,
                assetBytesByPath = emptyMap()
            ),
            plan = plan
        )
    }

    override suspend fun executeRestore(
        input: InputStream,
        pendingRestore: PendingRestore,
        onProgress: (Int) -> Unit,
    ): RestoreExecutionResult {
        val coroutineContext = currentCoroutineContext()
        requireRestoreContactsPermission()
        val selectedSource = currentSourceOrThrow()
        val archiveContent = archiveReader.read(
            input = input,
            includedAssetPaths = neededAssetPathsForRestore(pendingRestore.plan),
            onProgress = { readProgress ->
                onProgress(RESTORE_EXECUTION_READ_START_PROGRESS +
                        ((readProgress.coerceIn(0, 100) * RESTORE_EXECUTION_READ_PROGRESS_RANGE) / 100))
            },
            isCancelled = { !coroutineContext.isActive }
        )
        return restoreExecutor.execute(
            source = selectedSource,
            plan = pendingRestore.plan,
            assetBytesByPath = archiveContent.assetBytesByPath,
            onProgress = { executionProgress ->
                onProgress(RESTORE_EXECUTION_APPLY_START_PROGRESS +
                        ((executionProgress.coerceIn(0, 100) * RESTORE_EXECUTION_APPLY_PROGRESS_RANGE) / 100))
            }
        )
    }

    override fun canWriteSystemSettings(): Boolean = defaultToneManager.canWriteSystemSettings()

    override fun createManageWriteSettingsIntent(): Intent =
        defaultToneManager.createManageWriteSettingsIntent()

    override fun requireReadyForBackup() {
        requireReadContactsPermission()
        currentSourceOrThrow()
    }

    override fun requireReadyForRestore() {
        requireRestoreContactsPermission()
        currentSourceOrThrow()
    }

    override fun createDefaultBackupFileName(): String = createDefaultBackupFileName(Date())

    internal fun createDefaultBackupFileName(date: Date): String {
        val source = currentSourceOrThrow()
        val sourceName = when (source) {
            is ContactSource.CloudAccount -> source.account.name
            ContactSource.OnDevice -> "on-device"
        }.safeFileNameSegment()
        val prefix = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(date)
        return "$prefix-$sourceName$GRS_FILE_EXTENSION"
    }

    private fun currentSourceOrThrow(): ContactSource {
        val availableSources = sourceRepository.getSourcesAvailable()
        val selectedSource = sourceRepository.selected.value
        if (selectedSource != null && selectedSource in availableSources) {
            return selectedSource
        }
        if (availableSources.size == 1) {
            return availableSources.single()
        }
        throw BackupRestoreSourceSelectionException()
    }

    private fun requireReadContactsPermission() {
        if (!contactsHelper.hasReadContactsPermission()) {
            throw BackupRestoreContactsPermissionException()
        }
    }

    private fun requireRestoreContactsPermission() {
        if (!contactsHelper.hasReadContactsPermission() || !contactsHelper.hasWriteContactsPermission()) {
            throw BackupRestoreContactsPermissionException()
        }
    }

    private fun neededAssetPathsForRestore(plan: RestorePlan): Set<String> {
        val neededToneIds = linkedSetOf<String>()
        plan.groupTones.mapTo(neededToneIds) { groupTone -> groupTone.toneId }
        if (plan.defaultToneCount <= 0 || defaultToneManager.canWriteSystemSettings()) {
            plan.snapshot.defaultTones.mapTo(neededToneIds) { defaultTone -> defaultTone.toneId }
        }
        return plan.snapshot.tones
            .asSequence()
            .filter { tone -> tone.id in neededToneIds }
            .map { tone -> tone.archivePath }
            .toSet()
    }

    private companion object {
        const val SNAPSHOT_START_PROGRESS = 0
        const val SNAPSHOT_PROGRESS_RANGE = 55
        const val ARCHIVE_START_PROGRESS = 55
        const val ARCHIVE_PROGRESS_RANGE = 44
        const val RESTORE_READ_START_PROGRESS = 0
        const val RESTORE_READ_PROGRESS_RANGE = 95
        const val RESTORE_EXECUTION_READ_START_PROGRESS = 0
        const val RESTORE_EXECUTION_READ_PROGRESS_RANGE = 50
        const val RESTORE_EXECUTION_APPLY_START_PROGRESS = 50
        const val RESTORE_EXECUTION_APPLY_PROGRESS_RANGE = 50
    }
}

private fun String.safeFileNameSegment(): String =
    replace(Regex("""[\\/:*?"<>|\p{Cntrl}\s]+"""), "_")
        .trim('_')
        .ifBlank { "source" }
