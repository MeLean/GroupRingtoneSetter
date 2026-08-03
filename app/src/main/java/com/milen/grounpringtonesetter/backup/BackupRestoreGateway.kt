package com.milen.grounpringtonesetter.backup

import android.content.Intent
import java.io.InputStream
import java.io.OutputStream

/** Feature-facing backup contract, separated from ContactsProvider and archive implementation. */
internal interface BackupRestoreGateway {
    suspend fun exportBackup(
        output: OutputStream,
        onProgress: (Int) -> Unit = {},
    ): BackupExportResult

    suspend fun readAndPlanRestore(
        input: InputStream,
        onProgress: (Int) -> Unit = {},
    ): PendingRestore

    suspend fun executeRestore(
        input: InputStream,
        pendingRestore: PendingRestore,
        onProgress: (Int) -> Unit = {},
    ): RestoreExecutionResult

    fun canWriteSystemSettings(): Boolean

    fun createManageWriteSettingsIntent(): Intent

    fun requireReadyForBackup()

    fun requireReadyForRestore()

    fun createDefaultBackupFileName(): String
}
