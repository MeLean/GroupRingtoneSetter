package com.milen.grounpringtonesetter.ui.defaulttones.viewmodel

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.ui.ads.AdRuntimeTestApplication
import com.milen.grounpringtonesetter.testing.TestDispatcherProvider
import com.milen.grounpringtonesetter.ui.defaulttones.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = AdRuntimeTestApplication::class)
class DefaultToneRestorationTest {
    @Test
    fun `all system and file picker identities survive a new ViewModel and results apply once`() = runCase {
        DeviceDefaultToneType.entries.forEach { type ->
            listOf(TonePickerKind.SYSTEM, TonePickerKind.FILE).forEach { kind ->
                val manager = Manager()
                val handle = SavedStateHandle()
                val original = model(manager, handle)
                original.rememberPicker(kind, type)
                advanceUntilIdle()
                val restoredHandle = copy(handle)
                val restored = model(manager, restoredHandle)
                val uri = Uri.parse("content://test/tones/${type.name}")
                if (kind == TonePickerKind.SYSTEM) {
                    restored.onSystemPickerResult(true, uri)
                    restored.onSystemPickerResult(true, uri)
                } else {
                    restored.onFilePickerResult(uri)
                    restored.onFilePickerResult(uri)
                }
                advanceUntilIdle()
                assertEquals(listOf(type to uri), manager.applied)
                val completion = requireNotNull(restored.state.value.pendingAdCompletion)
                assertFalse(completion.isReady)
                assertTrue(restored.tryStartCompletionAd(completion.id))
                assertFalse(restored.tryStartCompletionAd(completion.id))
                val afterAnotherRecreation = model(manager, copy(restoredHandle))
                assertTrue(requireNotNull(afterAnotherRecreation.state.value.pendingAdCompletion).isReady)
                assertFalse(afterAnotherRecreation.tryStartCompletionAd(completion.id))
                afterAnotherRecreation.onCompletionDismissed(completion.id)
                advanceUntilIdle()
            }
        }
    }

    @Test
    fun `cancellation and repeated callbacks never assign a tone or show success`() = runCase {
        val manager = Manager()
        val originalHandle = SavedStateHandle()
        model(manager, originalHandle).rememberPicker(TonePickerKind.SYSTEM, DeviceDefaultToneType.RINGTONE)
        val restored = model(manager, copy(originalHandle))
        restored.onSystemPickerResult(false, null)
        restored.onSystemPickerResult(true, Uri.parse("content://test/unexpected"))
        restored.rememberPicker(TonePickerKind.FILE, DeviceDefaultToneType.ALARM)
        restored.onFilePickerResult(null)
        restored.onFilePickerResult(Uri.parse("content://test/unexpected"))
        advanceUntilIdle()
        assertTrue(manager.applied.isEmpty())
        assertNull(restored.state.value.pendingAdCompletion)
    }

    @Test
    fun `legacy permission identity and write settings pending selection survive recreation`() = runCase {
        val manager = Manager().apply { canWrite = false }
        val handle = SavedStateHandle()
        val original = model(manager, handle)
        original.rememberPicker(TonePickerKind.LEGACY_PERMISSION, DeviceDefaultToneType.ALARM)
        val restored = model(manager, copy(handle))
        assertEquals(DeviceDefaultToneType.ALARM, restored.consumePicker(TonePickerKind.LEGACY_PERMISSION))
        assertNull(restored.consumePicker(TonePickerKind.LEGACY_PERMISSION))
        val pending = SavedStateHandle()
        val waiting = model(manager, pending)
        val uri = Uri.parse("content://test/alarm")
        waiting.onTonePicked(DeviceDefaultToneType.ALARM, uri)
        advanceUntilIdle()
        assertTrue(manager.applied.isEmpty())
        waiting.onWriteSettingsDialogConfirmed()
        manager.canWrite = true
        val afterSettings = model(manager, copy(pending))
        afterSettings.onReturnedFromWriteSettings()
        afterSettings.onScreenResumed()
        advanceUntilIdle()
        assertEquals(listOf(DeviceDefaultToneType.ALARM to uri), manager.applied)
    }

    @Test
    fun `interrupted write reports success only when the OS already has the requested tone`() = runCase {
        listOf(false, true).forEach { osHasTone ->
            val manager = Manager().apply { interruptWrite = true }
            val handle = SavedStateHandle()
            val uri = Uri.parse("content://test/interrupted")
            model(manager, handle).onTonePicked(DeviceDefaultToneType.ALARM, uri)
            advanceUntilIdle()
            manager.interruptWrite = false
            if (osHasTone) manager.current[DeviceDefaultToneType.ALARM] = uri
            val restored = model(manager, copy(handle))
            advanceUntilIdle()
            assertTrue(manager.applied.isEmpty()) // No automatic replay of an uncertain write.
            if (osHasTone) {
                assertTrue(requireNotNull(restored.state.value.pendingAdCompletion).isReady)
            } else {
                assertNull(restored.state.value.pendingAdCompletion)
                assertEquals(DeviceDefaultTonesEvent.ShowApplyFailedDialog, restored.events.first())
            }
        }
    }

    @Test
    fun `interrupted import cannot report a false successful assignment`() = runCase {
        val manager = Manager().apply { interruptImport = true }
        val handle = SavedStateHandle()
        model(manager, handle).onCustomTonePicked(DeviceDefaultToneType.RINGTONE, Uri.parse("content://test/import"))
        advanceUntilIdle()
        val restored = model(manager, copy(handle))
        advanceUntilIdle()
        assertTrue(manager.applied.isEmpty())
        assertNull(restored.state.value.pendingAdCompletion)
        assertEquals(DeviceDefaultTonesEvent.ShowErrorById(R.string.default_tone_import_failed), restored.events.first())
    }

    private fun TestScope.model(manager: Manager, handle: SavedStateHandle) = DeviceDefaultTonesViewModel(
        onError = { throw AssertionError(it) }, toneManager = manager,
        entitlementState = MutableStateFlow(EntitlementState.NOT_OWNED),
        dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)), savedState = handle,
    )

    private fun copy(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
    private fun runCase(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block(); advanceUntilIdle() } finally { Dispatchers.resetMain() }
    }

    private class Manager : DeviceDefaultToneManager {
        var canWrite = true
        var interruptWrite = false
        var interruptImport = false
        val current = mutableMapOf<DeviceDefaultToneType, Uri?>()
        val applied = mutableListOf<Pair<DeviceDefaultToneType, Uri?>>()
        override fun canWriteSystemSettings() = canWrite
        override fun getCurrentDefaultUri(type: DeviceDefaultToneType) = current[type]
        override fun getCurrentDisplayName(type: DeviceDefaultToneType) = "Test tone"
        override fun validateToneSelection(type: DeviceDefaultToneType, uriOrNull: Uri?) = Result.success(Unit)
        override fun setDefaultTone(type: DeviceDefaultToneType, uriOrNull: Uri?): Result<Unit> {
            if (interruptWrite) throw CancellationException("Interrupted test write")
            applied += type to uriOrNull
            current[type] = uriOrNull
            return Result.success(Unit)
        }
        override fun importCustomTone(type: DeviceDefaultToneType, sourceUri: Uri): Result<ImportedCustomTone> {
            if (interruptImport) throw CancellationException("Interrupted test import")
            return Result.success(ImportedCustomTone(sourceUri, false))
        }
        override fun deleteImportedTone(uri: Uri) = Unit
        override fun createManageWriteSettingsIntent() = Intent()
        override fun createSoundSettingsIntent() = Intent()
    }
}
