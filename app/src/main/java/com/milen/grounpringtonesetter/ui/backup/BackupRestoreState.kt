package com.milen.grounpringtonesetter.ui.backup

import android.content.Intent
import androidx.annotation.StringRes
import com.milen.grounpringtonesetter.backup.BackupExportResult
import com.milen.grounpringtonesetter.backup.RestoreExecutionResult
import com.milen.grounpringtonesetter.backup.RestorePreview
import com.milen.grounpringtonesetter.billing.EntitlementState

internal const val BACKUP_RESTORE_RESULT_KEY = "backup_restore_result"
internal const val BACKUP_RESTORE_EXTRA_RESTORE_COMPLETED = "backup_restore_restore_completed"

internal data class BackupRestoreState(
    val entitlement: EntitlementState = EntitlementState.UNKNOWN,
    val isLoading: Boolean = false,
    val isPurchaseInProgress: Boolean = false,
    val exportProgressPercent: Int? = null,
    val restoreProgressPercent: Int? = null,
) {
    val isBusy: Boolean
        get() = isLoading || exportProgressPercent != null || restoreProgressPercent != null
}

internal sealed interface BackupRestoreEvent {
    data class CreateBackupDocument(val fileName: String) : BackupRestoreEvent
    data object OpenBackupDocument : BackupRestoreEvent
    data class ShowErrorById(@param:StringRes val messageResId: Int) : BackupRestoreEvent
    data class ShowInfoById(@param:StringRes val messageResId: Int) : BackupRestoreEvent
    data class ShowExportSummary(val result: BackupExportResult) : BackupRestoreEvent
    data class ShowRestorePreview(val preview: RestorePreview) : BackupRestoreEvent
    data class ShowRestoreResult(val result: RestoreExecutionResult) : BackupRestoreEvent
    data class OpenIntent(val intent: Intent) : BackupRestoreEvent
}
