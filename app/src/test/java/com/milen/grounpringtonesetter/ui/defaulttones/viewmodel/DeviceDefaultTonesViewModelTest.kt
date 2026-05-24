package com.milen.grounpringtonesetter.ui.defaulttones.viewmodel

import android.content.Intent
import android.net.FakeUri
import android.net.Uri
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.ui.defaulttones.DefaultToneImportException
import com.milen.grounpringtonesetter.ui.defaulttones.DefaultToneImportFailureReason
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneManager
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneType
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultTonesEvent
import com.milen.grounpringtonesetter.ui.defaulttones.ImportedCustomTone
import com.milen.grounpringtonesetter.utils.DispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val TEST_TONE_URI: Uri = FakeUri("content://test/tones/custom.mp3")

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDefaultTonesViewModelTest {

    @Test
    fun `missing write settings permission stores pending selection and applies it once on return`() =
        runViewModelTest { dispatcher ->
            val toneManager = FakeDeviceDefaultToneManager(canWrite = false)
            val viewModel = createViewModel(
                toneManager = toneManager,
                entitlement = EntitlementState.OWNED,
                dispatcher = dispatcher
            )
            val pickedUri: Uri? = null

            advanceUntilIdle()
            viewModel.onTonePicked(DeviceDefaultToneType.NOTIFICATION, pickedUri)

            val writeSettingsEvent = withTimeout(1_000) { viewModel.events.first() }
            assertEquals(DeviceDefaultTonesEvent.ShowWriteSettingsDialog, writeSettingsEvent)
            assertEquals(0, toneManager.appliedSelections.size)

            toneManager.canWrite = true
            viewModel.onReturnedFromWriteSettings()
            advanceUntilIdle()

            assertEquals(1, toneManager.appliedSelections.size)
            assertEquals(
                DeviceDefaultToneType.NOTIFICATION to pickedUri,
                toneManager.appliedSelections.first()
            )

            viewModel.onReturnedFromWriteSettings()
            advanceUntilIdle()
            assertEquals(1, toneManager.appliedSelections.size)
        }

    @Test
    fun `successful apply emits post ad success event only for NOT_OWNED entitlement`() =
        runViewModelTest { dispatcher ->
            val toneManager = FakeDeviceDefaultToneManager(canWrite = true)
            val viewModel = createViewModel(
                toneManager = toneManager,
                entitlement = EntitlementState.NOT_OWNED,
                dispatcher = dispatcher
            )

            advanceUntilIdle()
            viewModel.onTonePicked(
                type = DeviceDefaultToneType.NOTIFICATION,
                pickedUri = null
            )
            advanceUntilIdle()

            val event = withTimeout(1_000) {
                viewModel.events.filterIsInstance<DeviceDefaultTonesEvent.ShowInterstitialThenInfo>()
                    .first()
            }
            assertEquals(R.string.everything_set, event.messageResId)
            assertEquals(1, toneManager.appliedSelections.size)
        }

    @Test
    fun `successful apply does not emit interstitial for owned unknown or pending entitlement`() =
        runViewModelTest { dispatcher ->
            val adIneligibleStates = listOf(
                EntitlementState.OWNED,
                EntitlementState.UNKNOWN,
                EntitlementState.PENDING
            )

            adIneligibleStates.forEach { entitlement ->
                val toneManager = FakeDeviceDefaultToneManager(canWrite = true)
                val viewModel = createViewModel(
                    toneManager = toneManager,
                    entitlement = entitlement,
                    dispatcher = dispatcher
                )

                advanceUntilIdle()
                viewModel.onTonePicked(
                    type = DeviceDefaultToneType.ALARM,
                    pickedUri = null
                )
                advanceUntilIdle()

                val maybeInterstitial = withTimeoutOrNull(200) {
                    viewModel.events.filterIsInstance<DeviceDefaultTonesEvent.ShowInterstitialThenInfo>()
                        .first()
                }
                assertNull(maybeInterstitial)
                assertEquals(1, toneManager.appliedSelections.size)
            }
        }

    @Test
    fun `successful apply emits everything set info dialog event`() = runViewModelTest { dispatcher ->
        val toneManager = FakeDeviceDefaultToneManager(canWrite = true)
        val viewModel = createViewModel(
            toneManager = toneManager,
            entitlement = EntitlementState.OWNED,
            dispatcher = dispatcher
        )

        advanceUntilIdle()
        viewModel.onTonePicked(
            type = DeviceDefaultToneType.RINGTONE,
            pickedUri = TEST_TONE_URI
        )
        advanceUntilIdle()

        val event = withTimeout(1_000) {
            viewModel.events.filterIsInstance<DeviceDefaultTonesEvent.ShowInfoById>().first()
        }
        assertEquals(R.string.everything_set, event.messageResId)
    }

    @Test
    fun `apply failure emits fallback dialog event`() = runViewModelTest { dispatcher ->
        val toneManager = FakeDeviceDefaultToneManager(
            canWrite = true,
            setResult = Result.failure(IllegalStateException("set failed"))
        )
        val viewModel = createViewModel(
            toneManager = toneManager,
            entitlement = EntitlementState.NOT_OWNED,
            dispatcher = dispatcher
        )

        advanceUntilIdle()
        viewModel.onTonePicked(
            type = DeviceDefaultToneType.NOTIFICATION,
            pickedUri = null
        )
        advanceUntilIdle()

        val event = withTimeout(1_000) { viewModel.events.first() }
        assertEquals(DeviceDefaultTonesEvent.ShowApplyFailedDialog, event)
    }

    @Test
    fun `system notification tone longer than policy is rejected before apply`() =
        runViewModelTest { dispatcher ->
            val toneManager = FakeDeviceDefaultToneManager(
                canWrite = true,
                validateSelectionResult = Result.failure(
                    DefaultToneImportException(DefaultToneImportFailureReason.NOTIFICATION_TONE_TOO_LONG)
                )
            )
            val viewModel = createViewModel(
                toneManager = toneManager,
                entitlement = EntitlementState.OWNED,
                dispatcher = dispatcher
            )

            advanceUntilIdle()
            viewModel.onTonePicked(
                type = DeviceDefaultToneType.NOTIFICATION,
                pickedUri = TEST_TONE_URI
            )
            advanceUntilIdle()

            val event = withTimeout(1_000) { viewModel.events.first() }
            assertTrue(event is DeviceDefaultTonesEvent.ShowErrorById)
            assertEquals(
                R.string.default_tone_notification_too_long,
                (event as DeviceDefaultTonesEvent.ShowErrorById).messageResId
            )
            assertEquals(0, toneManager.appliedSelections.size)
        }

    @Test
    fun `custom import success with missing permission keeps pending and applies once after grant`() =
        runViewModelTest { dispatcher ->
            val toneManager = FakeDeviceDefaultToneManager(
                canWrite = false,
                importResult = Result.success(
                    ImportedCustomTone(uri = TEST_TONE_URI, wasNewlyCreated = true)
                )
            )
            val viewModel = createViewModel(
                toneManager = toneManager,
                entitlement = EntitlementState.OWNED,
                dispatcher = dispatcher
            )

            advanceUntilIdle()
            viewModel.onCustomTonePicked(DeviceDefaultToneType.NOTIFICATION, TEST_TONE_URI)
            advanceUntilIdle()

            val writeSettingsEvent = withTimeout(1_000) { viewModel.events.first() }
            assertEquals(DeviceDefaultTonesEvent.ShowWriteSettingsDialog, writeSettingsEvent)
            assertEquals(1, toneManager.importCalls.size)
            assertEquals(0, toneManager.appliedSelections.size)

            toneManager.canWrite = true
            viewModel.onReturnedFromWriteSettings()
            advanceUntilIdle()

            assertEquals(1, toneManager.appliedSelections.size)

            viewModel.onReturnedFromWriteSettings()
            advanceUntilIdle()
            assertEquals(1, toneManager.appliedSelections.size)
        }

    @Test
    fun `custom import failure emits typed error message`() = runViewModelTest { dispatcher ->
        val toneManager = FakeDeviceDefaultToneManager(
            canWrite = true,
            importResult = Result.failure(
                DefaultToneImportException(DefaultToneImportFailureReason.NOTIFICATION_TONE_TOO_LONG)
            )
        )
        val viewModel = createViewModel(
            toneManager = toneManager,
            entitlement = EntitlementState.OWNED,
            dispatcher = dispatcher
        )

        advanceUntilIdle()
        viewModel.onCustomTonePicked(DeviceDefaultToneType.NOTIFICATION, TEST_TONE_URI)
        advanceUntilIdle()

        val event = withTimeout(1_000) { viewModel.events.first() }
        assertTrue(event is DeviceDefaultTonesEvent.ShowErrorById)
        assertEquals(
            R.string.default_tone_notification_too_long,
            (event as DeviceDefaultTonesEvent.ShowErrorById).messageResId
        )
        assertEquals(1, toneManager.importCalls.size)
        assertEquals(0, toneManager.appliedSelections.size)
    }

    @Test
    fun `denied write settings after custom import clears pending and removes imported uri`() =
        runViewModelTest { dispatcher ->
            val toneManager = FakeDeviceDefaultToneManager(
                canWrite = false,
                importResult = Result.success(
                    ImportedCustomTone(uri = TEST_TONE_URI, wasNewlyCreated = true)
                )
            )
            val viewModel = createViewModel(
                toneManager = toneManager,
                entitlement = EntitlementState.OWNED,
                dispatcher = dispatcher
            )

            advanceUntilIdle()
            viewModel.onCustomTonePicked(DeviceDefaultToneType.NOTIFICATION, TEST_TONE_URI)
            val firstEvent = withTimeout(1_000) { viewModel.events.first() }
            assertEquals(DeviceDefaultTonesEvent.ShowWriteSettingsDialog, firstEvent)

            viewModel.onWriteSettingsDialogConfirmed()
            withTimeout(1_000) {
                viewModel.events.filterIsInstance<DeviceDefaultTonesEvent.OpenIntent>().first()
            }

            viewModel.onReturnedFromWriteSettings()
            advanceUntilIdle()

            assertEquals(1, toneManager.deletedImportedUris.size)
            assertEquals(TEST_TONE_URI.toString(), toneManager.deletedImportedUris.first().toString())
            assertEquals(0, toneManager.appliedSelections.size)
        }

    @Test
    fun `denied write settings does not remove reused custom tone`() =
        runViewModelTest { dispatcher ->
            val toneManager = FakeDeviceDefaultToneManager(
                canWrite = false,
                importResult = Result.success(
                    ImportedCustomTone(uri = TEST_TONE_URI, wasNewlyCreated = false)
                )
            )
            val viewModel = createViewModel(
                toneManager = toneManager,
                entitlement = EntitlementState.OWNED,
                dispatcher = dispatcher
            )

            advanceUntilIdle()
            viewModel.onCustomTonePicked(DeviceDefaultToneType.NOTIFICATION, TEST_TONE_URI)
            val firstEvent = withTimeout(1_000) { viewModel.events.first() }
            assertEquals(DeviceDefaultTonesEvent.ShowWriteSettingsDialog, firstEvent)

            viewModel.onWriteSettingsDialogConfirmed()
            withTimeout(1_000) {
                viewModel.events.filterIsInstance<DeviceDefaultTonesEvent.OpenIntent>().first()
            }

            viewModel.onReturnedFromWriteSettings()
            advanceUntilIdle()

            assertEquals(0, toneManager.deletedImportedUris.size)
            assertEquals(0, toneManager.appliedSelections.size)
        }

    private fun runViewModelTest(
        block: suspend TestScope.(TestDispatcher) -> Unit,
    ) = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        try {
            block(mainDispatcher)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(
        toneManager: FakeDeviceDefaultToneManager,
        entitlement: EntitlementState,
        dispatcher: TestDispatcher,
    ): DeviceDefaultTonesViewModel {
        return DeviceDefaultTonesViewModel(
            onError = {},
            toneManager = toneManager,
            entitlementState = MutableStateFlow(entitlement),
            dispatchers = TestDispatcherProvider(dispatcher)
        )
    }
}

private class TestDispatcherProvider(
    dispatcher: TestDispatcher,
) : DispatcherProvider {
    override val io = dispatcher
    override val default = dispatcher
    override val main = dispatcher
    override val mainImmediate = dispatcher
}

private class FakeDeviceDefaultToneManager(
    var canWrite: Boolean = true,
    var validateSelectionResult: Result<Unit> = Result.success(Unit),
    var importResult: Result<ImportedCustomTone> = Result.success(
        ImportedCustomTone(uri = TEST_TONE_URI, wasNewlyCreated = true)
    ),
    var setResult: Result<Unit> = Result.success(Unit),
) : DeviceDefaultToneManager {
    val importCalls = mutableListOf<Pair<DeviceDefaultToneType, Uri>>()
    val appliedSelections = mutableListOf<Pair<DeviceDefaultToneType, Uri?>>()
    val deletedImportedUris = mutableListOf<Uri>()
    private val currentUriByType = mutableMapOf<DeviceDefaultToneType, Uri?>(
        DeviceDefaultToneType.RINGTONE to null,
        DeviceDefaultToneType.NOTIFICATION to null,
        DeviceDefaultToneType.ALARM to null
    )

    private val displayNameByType = mutableMapOf(
        DeviceDefaultToneType.RINGTONE to "Default ringtone",
        DeviceDefaultToneType.NOTIFICATION to "Default notification",
        DeviceDefaultToneType.ALARM to "Default alarm"
    )

    override fun canWriteSystemSettings(): Boolean = canWrite

    override fun createManageWriteSettingsIntent(): Intent =
        Intent("test.action.MANAGE_WRITE_SETTINGS")

    override fun createSoundSettingsIntent(): Intent = Intent("test.action.SOUND_SETTINGS")

    override fun getCurrentDefaultUri(type: DeviceDefaultToneType): Uri? = currentUriByType[type]

    override fun getCurrentDisplayName(type: DeviceDefaultToneType): String =
        displayNameByType.getValue(type)

    override fun validateToneSelection(type: DeviceDefaultToneType, uriOrNull: Uri?): Result<Unit> {
        return validateSelectionResult
    }

    override fun importCustomTone(
        type: DeviceDefaultToneType,
        sourceUri: Uri
    ): Result<ImportedCustomTone> {
        importCalls += type to sourceUri
        return importResult
    }

    override fun deleteImportedTone(uri: Uri) {
        deletedImportedUris += uri
    }

    override fun setDefaultTone(type: DeviceDefaultToneType, uriOrNull: Uri?): Result<Unit> {
        appliedSelections += type to uriOrNull
        return setResult.onSuccess {
            currentUriByType[type] = uriOrNull
            displayNameByType[type] = when {
                uriOrNull == null && type.allowSilentSelection -> "Silent"
                uriOrNull == null -> "Unknown"
                else -> "Updated ${type.name}"
            }
        }
    }
}
