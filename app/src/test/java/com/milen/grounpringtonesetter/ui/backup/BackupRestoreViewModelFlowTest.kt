package com.milen.grounpringtonesetter.ui.backup

import android.content.Intent
import android.net.FakeUri
import android.net.Uri
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.backup.BackupArchiveContent
import com.milen.grounpringtonesetter.backup.BackupExportResult
import com.milen.grounpringtonesetter.backup.BackupRestoreGateway
import com.milen.grounpringtonesetter.backup.GRS_SCHEMA_VERSION
import com.milen.grounpringtonesetter.backup.GrsBackupSnapshot
import com.milen.grounpringtonesetter.backup.PendingRestore
import com.milen.grounpringtonesetter.backup.RestoreExecutionResult
import com.milen.grounpringtonesetter.backup.RestorePlan
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.testing.FakeBillingGateway
import com.milen.grounpringtonesetter.testing.RecordingTelemetry
import com.milen.grounpringtonesetter.testing.TestDispatcherProvider
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

private val BACKUP_URI: Uri = FakeUri("content://test/backup.grs")

@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreViewModelFlowTest {

    @Test
    fun `locked entitlement rejects export without repository access`() = runBackupTest(
        entitlement = EntitlementState.NOT_OWNED,
    ) { fixture ->
        fixture.viewModel.onExportClicked()

        assertEquals(
            BackupRestoreEvent.ShowErrorById(R.string.backup_restore_locked_message),
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
        assertEquals(0, fixture.repository.readyForBackupCalls)
    }

    @Test
    fun `owned export requests destination with deterministic repository filename`() =
        runBackupTest { fixture ->
            fixture.viewModel.onExportClicked()

            assertEquals(
                BackupRestoreEvent.CreateBackupDocument("2026-08-03-on-device.grs"),
                withTimeout(1_000) { fixture.viewModel.events.first() },
            )
            assertEquals(1, fixture.repository.readyForBackupCalls)
        }

    @Test
    fun `created destination exports stream and emits summary`() = runBackupTest { fixture ->
        fixture.viewModel.onBackupDocumentCreated(BACKUP_URI)
        advanceUntilIdle()

        assertEquals(1, fixture.repository.exportCalls)
        assertEquals(
            BackupRestoreEvent.ShowExportSummary(fixture.repository.exportResult),
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
        assertTrue(fixture.documents.output.toByteArray().isNotEmpty())
        assertNull(fixture.viewModel.state.value.exportProgressPercent)
    }

    @Test
    fun `missing output stream reports generic error and clears progress`() = runBackupTest { fixture ->
        fixture.documents.outputAvailable = false
        fixture.viewModel.onBackupDocumentCreated(BACKUP_URI)
        advanceUntilIdle()

        assertEquals(
            BackupRestoreEvent.ShowErrorById(R.string.something_went_wrong),
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
        assertEquals(1, fixture.telemetry.errors.size)
        assertNull(fixture.viewModel.state.value.exportProgressPercent)
    }

    @Test
    fun `restore click validates readiness then opens document picker`() = runBackupTest { fixture ->
        fixture.viewModel.onRestoreClicked()

        assertEquals(
            BackupRestoreEvent.OpenBackupDocument,
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
        assertEquals(1, fixture.repository.readyForRestoreCalls)
    }

    @Test
    fun `wrong restore extension is rejected before archive read`() = runBackupTest { fixture ->
        fixture.documents.documentMetadata = BackupDocumentMetadata(
            displayName = "backup.zip",
            sizeBytes = 42,
            mimeType = "application/zip",
        )
        fixture.viewModel.onBackupDocumentPicked(BACKUP_URI)
        advanceUntilIdle()

        assertEquals(
            BackupRestoreEvent.ShowErrorById(R.string.backup_restore_invalid_file),
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
        assertEquals(0, fixture.repository.readCalls)
    }

    @Test
    fun `valid backup emits preview and records non sensitive metadata`() = runBackupTest { fixture ->
        fixture.viewModel.onBackupDocumentPicked(BACKUP_URI)
        advanceUntilIdle()

        val event = withTimeout(1_000) { fixture.viewModel.events.first() }
        assertTrue(event is BackupRestoreEvent.ShowRestorePreview)
        assertEquals(1, fixture.repository.readCalls)
        assertTrue(fixture.telemetry.events.any { it.first == "grs_restore_file_selected" })
        assertNull(fixture.viewModel.state.value.restoreProgressPercent)
    }

    @Test
    fun `restore confirmation requests write settings when default tones need permission`() =
        runBackupTest { fixture ->
            fixture.repository.canWriteSettings = false
            fixture.viewModel.onBackupDocumentPicked(BACKUP_URI)
            advanceUntilIdle()
            withTimeout(1_000) { fixture.viewModel.events.first() }

            fixture.viewModel.onRestoreConfirmed()

            val event = withTimeout(1_000) { fixture.viewModel.events.first() }
            assertTrue(event is BackupRestoreEvent.OpenIntent)
            assertEquals(0, fixture.repository.executeCalls)
        }

    @Test
    fun `restore executes once and clears pending state after result`() = runBackupTest { fixture ->
        fixture.repository.canWriteSettings = true
        fixture.viewModel.onBackupDocumentPicked(BACKUP_URI)
        advanceUntilIdle()
        withTimeout(1_000) { fixture.viewModel.events.first() }

        fixture.viewModel.onRestoreConfirmed()
        advanceUntilIdle()

        assertEquals(1, fixture.repository.executeCalls)
        assertEquals(
            BackupRestoreEvent.ShowRestoreResult(fixture.repository.executionResult),
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
        fixture.viewModel.onReturnedFromWriteSettings()
        advanceUntilIdle()
        assertEquals(1, fixture.repository.executeCalls)
        assertFalse(fixture.viewModel.state.value.isBusy)
    }

    private fun runBackupTest(
        entitlement: EntitlementState = EntitlementState.OWNED,
        block: suspend TestScope.(BackupFixture) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val originalDispatchers = DispatchersProvider.delegate
        Dispatchers.setMain(dispatcher)
        DispatchersProvider.delegate = TestDispatcherProvider(dispatcher)
        try {
            val repository = FakeBackupRestoreGateway()
            val documents = FakeBackupDocumentGateway()
            val telemetry = RecordingTelemetry()
            val viewModel = BackupRestoreViewModel(
                documents = documents,
                repository = repository,
                billing = FakeBillingGateway(entitlement),
                tracker = telemetry,
            )
            advanceUntilIdle()
            block(BackupFixture(viewModel, repository, documents, telemetry))
        } finally {
            DispatchersProvider.delegate = originalDispatchers
            Dispatchers.resetMain()
        }
    }
}

private data class BackupFixture(
    val viewModel: BackupRestoreViewModel,
    val repository: FakeBackupRestoreGateway,
    val documents: FakeBackupDocumentGateway,
    val telemetry: RecordingTelemetry,
)

private class FakeBackupDocumentGateway : BackupDocumentGateway {
    val output = ByteArrayOutputStream()
    var outputAvailable = true
    var inputAvailable = true
    var documentMetadata = BackupDocumentMetadata(
        displayName = "backup.grs",
        sizeBytes = 42,
        mimeType = "application/vnd.group-ringtone-setter.backup",
    )

    override fun openInputStream(uri: Uri): InputStream? =
        if (inputAvailable) ByteArrayInputStream(byteArrayOf(1, 2, 3)) else null

    override fun openOutputStream(uri: Uri): OutputStream? =
        if (outputAvailable) output else null

    override fun metadata(uri: Uri): BackupDocumentMetadata = documentMetadata
}

private class FakeBackupRestoreGateway : BackupRestoreGateway {
    var readyForBackupCalls = 0
    var readyForRestoreCalls = 0
    var exportCalls = 0
    var readCalls = 0
    var executeCalls = 0
    var canWriteSettings = true
    val exportResult = BackupExportResult(1, 1, 1)
    val executionResult = RestoreExecutionResult(1, 2, 1, 0, 0)
    private val snapshot = GrsBackupSnapshot(
        createdAtEpochMillis = 1L,
        appVersionName = "test",
        sourceLabel = "on-device",
        groupTones = emptyList(),
        defaultTones = listOf(
            com.milen.grounpringtonesetter.backup.GrsDefaultToneSnapshot(
                type = com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneType.RINGTONE,
                toneId = "tone-1",
                displayName = "Tone",
            )
        ),
        tones = emptyList(),
    )
    private val pendingRestore = PendingRestore(
        archiveContent = BackupArchiveContent(snapshot, emptyMap()),
        plan = RestorePlan(snapshot, emptyList()),
    )

    override suspend fun exportBackup(
        output: OutputStream,
        onProgress: (Int) -> Unit,
    ): BackupExportResult {
        exportCalls += 1
        onProgress(10)
        onProgress(100)
        output.write(byteArrayOf(GRS_SCHEMA_VERSION.toByte()))
        return exportResult
    }

    override suspend fun readAndPlanRestore(
        input: InputStream,
        onProgress: (Int) -> Unit,
    ): PendingRestore {
        readCalls += 1
        onProgress(25)
        onProgress(100)
        return pendingRestore
    }

    override suspend fun executeRestore(
        input: InputStream,
        pendingRestore: PendingRestore,
        onProgress: (Int) -> Unit,
    ): RestoreExecutionResult {
        executeCalls += 1
        onProgress(50)
        onProgress(100)
        return executionResult
    }

    override fun canWriteSystemSettings(): Boolean = canWriteSettings

    override fun createManageWriteSettingsIntent(): Intent = Intent("test.manage.write.settings")

    override fun requireReadyForBackup() {
        readyForBackupCalls += 1
    }

    override fun requireReadyForRestore() {
        readyForRestoreCalls += 1
    }

    override fun createDefaultBackupFileName(): String = "2026-08-03-on-device.grs"
}
