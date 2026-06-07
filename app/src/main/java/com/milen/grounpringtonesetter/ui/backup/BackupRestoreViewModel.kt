package com.milen.grounpringtonesetter.ui.backup

import android.app.Activity
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.backup.BackupRestoreContactsPermissionException
import com.milen.grounpringtonesetter.backup.BackupRestoreRepository
import com.milen.grounpringtonesetter.backup.BackupRestoreSourceSelectionException
import com.milen.grounpringtonesetter.backup.GRS_FILE_EXTENSION
import com.milen.grounpringtonesetter.backup.GrsArchiveException
import com.milen.grounpringtonesetter.backup.PendingRestore
import com.milen.grounpringtonesetter.backup.RestoreDefaultTonePermissionPolicy
import com.milen.grounpringtonesetter.billing.BillingEntitlementManager
import com.milen.grounpringtonesetter.billing.BillingResultMessageResolver
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import com.milen.grounpringtonesetter.utils.Tracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

internal class BackupRestoreViewModel(
    private val contentResolver: ContentResolver,
    private val repository: BackupRestoreRepository,
    private val billing: BillingEntitlementManager,
    private val tracker: Tracker,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupRestoreState())
    val state: StateFlow<BackupRestoreState> = _state

    private val _events = Channel<BackupRestoreEvent>(Channel.BUFFERED)
    val events: Flow<BackupRestoreEvent> = _events.receiveAsFlow()

    private var pendingRestore: PendingRestore? = null
    private var pendingRestoreUri: Uri? = null
    private var exportJob: Job? = null
    private var restoreJob: Job? = null

    init {
        viewModelScope.launch {
            billing.state.collect { entitlement ->
                _state.update { it.copy(entitlement = entitlement) }
            }
        }
    }

    fun onExportClicked() {
        if (!canUseFeature()) return
        val fileName = runCatching {
            repository.requireReadyForBackup()
            repository.createDefaultBackupFileName()
        }.getOrElse { error ->
            handleBackupFailure(error)
            return
        }
        _events.trySend(BackupRestoreEvent.CreateBackupDocument(fileName))
    }

    fun onBackupDocumentCreated(uri: Uri?) {
        uri ?: return
        if (exportJob?.isActive == true) return

        exportJob = viewModelScope.launch {
            showExportProgress(0)
            try {
                val exportResult = withContext(DispatchersProvider.io) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        repository.exportBackup(
                            output = output,
                            onProgress = ::showExportProgress
                        )
                    } ?: throw IllegalStateException("Backup output stream unavailable")
                }
                _events.trySend(BackupRestoreEvent.ShowExportSummary(exportResult))
            } catch (_: CancellationException) {
                tracker.trackEvent("grs_backup_export_cancelled")
            } catch (error: Throwable) {
                handleBackupFailure(error)
            } finally {
                hideExportProgress()
                exportJob = null
            }
        }
    }

    fun onExportCancelled() {
        exportJob?.cancel()
    }

    fun onRestoreClicked() {
        if (!canUseFeature()) return
        runCatching {
            repository.requireReadyForRestore()
        }.onFailure { error ->
            handleBackupFailure(error)
            return
        }
        _events.trySend(BackupRestoreEvent.OpenBackupDocument)
    }

    fun onBackupDocumentPicked(uri: Uri) {
        if (restoreJob?.isActive == true) return

        restoreJob = viewModelScope.launch {
            showRestoreProgress(0)
            try {
                trackRestoreFileSelected(uri)
                validateBackupDocumentName(uri)
                val restore = withContext(DispatchersProvider.io) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        repository.readAndPlanRestore(
                            input = input,
                            onProgress = ::showRestoreProgress
                        )
                    } ?: throw IllegalStateException("Backup input stream unavailable")
                }
                pendingRestore = restore
                pendingRestoreUri = uri
                _events.trySend(BackupRestoreEvent.ShowRestorePreview(restore.plan.toPreview()))
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    tracker.trackEvent("grs_restore_cancelled")
                    clearPendingRestore()
                    return@launch
                }
                clearPendingRestore()
                handleBackupFailure(error)
            } finally {
                hideRestoreProgress()
                restoreJob = null
            }
        }
    }

    private fun validateBackupDocumentName(uri: Uri) {
        val displayName = uri.openableDisplayName() ?: return
        if (!displayName.endsWith(GRS_FILE_EXTENSION, ignoreCase = true)) {
            throw GrsArchiveException("Selected file is not a $GRS_FILE_EXTENSION backup")
        }
    }

    private fun trackRestoreFileSelected(uri: Uri) {
        tracker.trackEvent(
            "grs_restore_file_selected",
            mapOf(
                "scheme" to uri.scheme.orEmpty().ifBlank { "unknown" },
                "mimeType" to runCatching { contentResolver.getType(uri) }.getOrNull()
                    .orEmpty()
                    .ifBlank { "unknown" },
                "sizeBytes" to (uri.openableSizeBytes()?.toString() ?: "unknown")
            )
        )
    }

    fun onRestoreConfirmed() {
        val restore = pendingRestore ?: return
        val shouldRequestWriteSettings = RestoreDefaultTonePermissionPolicy.shouldRequestWriteSettings(
            defaultToneCount = restore.plan.defaultToneCount,
            canWriteSystemSettings = repository.canWriteSystemSettings()
        )
        if (shouldRequestWriteSettings) {
            _events.trySend(BackupRestoreEvent.OpenIntent(repository.createManageWriteSettingsIntent()))
            return
        }
        executePendingRestore()
    }

    fun onReturnedFromWriteSettings() {
        pendingRestore ?: return
        executePendingRestore()
    }

    fun onRestoreCancelled() {
        restoreJob?.cancel()
        clearPendingRestore()
    }

    fun startPurchase(activity: Activity) {
        if (_state.value.isPurchaseInProgress) return
        viewModelScope.launch {
            _state.update { it.copy(isPurchaseInProgress = true, isLoading = true) }
            val result = runCatching { billing.launchPurchase(activity) }
            result.onSuccess { code ->
                BillingResultMessageResolver.resolveMessageResId(code)?.let { messageResId ->
                    _events.trySend(BackupRestoreEvent.ShowInfoById(messageResId))
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                tracker.trackError(error)
                _events.trySend(BackupRestoreEvent.ShowErrorById(R.string.purchase_unavailable))
            }
            _state.update { it.copy(isPurchaseInProgress = false, isLoading = false) }
        }
    }

    private fun executePendingRestore() {
        if (restoreJob?.isActive == true) return
        val restore = pendingRestore ?: return
        val uri = pendingRestoreUri ?: return
        restoreJob = viewModelScope.launch {
            showRestoreProgress(0)
            try {
                val restoreResult = withContext(DispatchersProvider.io) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        repository.executeRestore(
                            input = input,
                            pendingRestore = restore,
                            onProgress = ::showRestoreProgress
                        )
                    } ?: throw IllegalStateException("Backup input stream unavailable")
                }
                clearPendingRestore()
                _events.trySend(BackupRestoreEvent.ShowRestoreResult(restoreResult))
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    tracker.trackEvent("grs_restore_cancelled")
                    clearPendingRestore()
                    return@launch
                }
                clearPendingRestore()
                handleBackupFailure(error)
            } finally {
                hideRestoreProgress()
                restoreJob = null
            }
        }
    }

    private fun clearPendingRestore() {
        pendingRestore = null
        pendingRestoreUri = null
    }

    private fun canUseFeature(): Boolean {
        if (_state.value.entitlement == EntitlementState.OWNED) return true
        _events.trySend(BackupRestoreEvent.ShowErrorById(R.string.backup_restore_locked_message))
        return false
    }

    private fun handleBackupFailure(error: Throwable) {
        if (error is CancellationException) throw error
        tracker.trackError(error)
        val messageResId = when (error) {
            is BackupRestoreSourceSelectionException -> R.string.backup_restore_select_source_required
            is BackupRestoreContactsPermissionException -> R.string.backup_restore_contacts_permission_required
            is GrsArchiveException -> R.string.backup_restore_invalid_file
            else -> R.string.something_went_wrong
        }
        _events.trySend(BackupRestoreEvent.ShowErrorById(messageResId))
    }

    private fun showExportProgress(percent: Int) {
        val normalizedPercent = percent.coerceIn(0, 100)
        _state.update { state ->
            val currentPercent = state.exportProgressPercent
            if (currentPercent != null && normalizedPercent < currentPercent) {
                state
            } else {
                state.copy(exportProgressPercent = normalizedPercent)
            }
        }
    }

    private fun hideExportProgress() {
        _state.update { it.copy(exportProgressPercent = null) }
    }

    private fun showRestoreProgress(percent: Int) {
        val normalizedPercent = percent.coerceIn(0, 100)
        _state.update { state ->
            val currentPercent = state.restoreProgressPercent
            if (currentPercent != null && normalizedPercent < currentPercent) {
                state
            } else {
                state.copy(restoreProgressPercent = normalizedPercent)
            }
        }
    }

    private fun hideRestoreProgress() {
        _state.update { it.copy(restoreProgressPercent = null) }
    }

    private fun Uri.openableSizeBytes(): Long? =
        runCatching {
            contentResolver.query(
                this,
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

    private fun Uri.openableDisplayName(): String? =
        runCatching {
            contentResolver.query(
                this,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
            }
        }.getOrNull()
}
