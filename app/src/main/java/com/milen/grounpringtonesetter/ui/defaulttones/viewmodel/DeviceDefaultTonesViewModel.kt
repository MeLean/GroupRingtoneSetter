package com.milen.grounpringtonesetter.ui.defaulttones.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.ui.RingtoneCompletionStore
import com.milen.grounpringtonesetter.ui.defaulttones.DefaultToneImportException
import com.milen.grounpringtonesetter.ui.defaulttones.DefaultToneImportFailureReason
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneManager
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneType
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultTonesEvent
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultTonesState
import com.milen.grounpringtonesetter.ui.defaulttones.TonePickerKind
import com.milen.grounpringtonesetter.ui.defaulttones.TonePickerLaunchConfig
import com.milen.grounpringtonesetter.utils.DispatcherProvider
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

internal class DeviceDefaultTonesViewModel(
    private val onError: (Throwable) -> Unit,
    private val toneManager: DeviceDefaultToneManager,
    private val entitlementState: StateFlow<EntitlementState>,
    private val dispatchers: DispatcherProvider = DispatchersProvider,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private data class PendingToneSelection(
        val toneType: DeviceDefaultToneType,
        val uri: Uri?,
        val cleanupImportedOnDiscard: Boolean,
    )

    private val completions = RingtoneCompletionStore(savedState)
    private val _state = MutableStateFlow(DeviceDefaultTonesState(pendingAdCompletion = completions.current))
    val state: StateFlow<DeviceDefaultTonesState> = _state

    private val _events = Channel<DeviceDefaultTonesEvent>(Channel.BUFFERED)
    val events: Flow<DeviceDefaultTonesEvent> = _events.receiveAsFlow()

    private var pendingToneSelection: PendingToneSelection? = restoreSelection()
    private var openedWriteSettingsForPendingSelection: Boolean
        get() = savedState[SETTINGS_OPENED] ?: false
        set(value) { savedState[SETTINGS_OPENED] = value }

    init {
        val phase = savedState.get<String>(PHASE)
        if (phase == "applying") {
            val selection = pendingToneSelection
            pendingToneSelection = null
            viewModelScope.launch {
                val applied = selection != null && runCatching {
                    withContext(dispatchers.io) { toneManager.getCurrentDefaultUri(selection.toneType) == selection.uri }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    onError(error)
                }.getOrDefault(false)
                clearSelection()
                if (applied) recordSuccess(allowAd = false)
                else _events.trySend(DeviceDefaultTonesEvent.ShowApplyFailedDialog)
            }
        } else if (phase == "importing" || phase == "validating") {
            // No safe proof of a completed write or a durable external URI grant.
            pendingToneSelection = null
            clearSelection()
            _events.trySend(DeviceDefaultTonesEvent.ShowErrorById(R.string.default_tone_import_failed))
        }
        refreshToneNames()
    }

    fun rememberPicker(kind: TonePickerKind, type: DeviceDefaultToneType) {
        savedState["picker_${kind.name}"] = type.name
        savedState["picker_${kind.name}_consumed"] = false
    }

    fun consumePicker(kind: TonePickerKind): DeviceDefaultToneType? {
        val name = savedState.remove<String>("picker_${kind.name}") ?: return null
        val type = DeviceDefaultToneType.entries.find { it.name == name }
        savedState["picker_${kind.name}_consumed"] = type != null
        return type
    }

    fun onSystemPickerResult(accepted: Boolean, uri: Uri?) {
        val type = consumePicker(TonePickerKind.SYSTEM)
        if (type == null) {
            if (accepted && savedState.get<Boolean>("picker_SYSTEM_consumed") != true) {
                _events.trySend(DeviceDefaultTonesEvent.ShowApplyFailedDialog)
            }
            return
        }
        if (!accepted) return
        if (uri == null && !type.allowSilentSelection) {
            _events.trySend(DeviceDefaultTonesEvent.ShowApplyFailedDialog)
            return
        }
        onTonePicked(type, uri)
    }

    fun onFilePickerResult(uri: Uri?) {
        val type = consumePicker(TonePickerKind.FILE)
        if (type == null) {
            if (uri != null && savedState.get<Boolean>("picker_FILE_consumed") != true) {
                _events.trySend(DeviceDefaultTonesEvent.ShowErrorById(R.string.default_tone_import_failed))
            }
            return
        }
        if (uri != null) onCustomTonePicked(type, uri)
    }

    fun tryStartCompletionAd(id: Long): Boolean = completions.tryStartAd(id)

    fun onInterstitialFinished(completionId: Long) {
        completions.finishAd(completionId)
        _state.update { it.copy(pendingAdCompletion = completions.current) }
    }

    fun onCompletionDismissed(completionId: Long) {
        completions.acknowledge(completionId)
        _state.update { it.copy(pendingAdCompletion = completions.current) }
    }

    private fun recordSuccess(allowAd: Boolean = true) {
        val completion = completions.record(allowAd && entitlementState.value == EntitlementState.NOT_OWNED)
        _state.update { it.copy(pendingAdCompletion = completion) }
    }

    private fun restoreSelection(): PendingToneSelection? =
        savedState.get<String>(SELECTION_TYPE)?.let { name ->
            DeviceDefaultToneType.entries.find { it.name == name }?.let { type ->
                PendingToneSelection(type, savedState.get<String>(SELECTION_URI)?.let(Uri::parse), savedState[SELECTION_IMPORTED] ?: false)
            }
        }

    private fun saveSelection(selection: PendingToneSelection) {
        savedState[SELECTION_TYPE] = selection.toneType.name
        savedState[SELECTION_URI] = selection.uri?.toString()
        savedState[SELECTION_IMPORTED] = selection.cleanupImportedOnDiscard
        savedState[PHASE] = "waiting_for_settings"
    }

    private fun clearSelection() {
        savedState.remove<String>(SELECTION_TYPE)
        savedState.remove<String>(SELECTION_URI)
        savedState.remove<Boolean>(SELECTION_IMPORTED)
        savedState.remove<String>(PHASE)
    }

    fun onScreenResumed() {
        applyPendingSelectionIfAllowed()
        refreshToneNames()
    }

    fun onToneChangeClicked(type: DeviceDefaultToneType) {
        viewModelScope.launch {
            val existingUri = runCatching {
                withContext(dispatchers.io) {
                    toneManager.getCurrentDefaultUri(type)
                }
            }.onFailure {
                if (it is CancellationException) throw it
                onError(it)
            }
                .getOrNull()

            _events.trySend(
                DeviceDefaultTonesEvent.LaunchTonePicker(
                    TonePickerLaunchConfig(
                        toneType = type,
                        existingUri = existingUri
                    )
                )
            )
        }
    }

    fun onTonePicked(type: DeviceDefaultToneType, pickedUri: Uri?) {
        savedState[PHASE] = "validating"
        viewModelScope.launch {
            val validationResult = runCatching {
                withContext(dispatchers.io) {
                    toneManager.validateToneSelection(type, pickedUri).getOrThrow()
                }
            }

            validationResult.onSuccess {
                pendingToneSelection = PendingToneSelection(
                    toneType = type,
                    uri = pickedUri,
                    cleanupImportedOnDiscard = false
                )
                pendingToneSelection?.let(::saveSelection)
                applyPendingSelectionIfAllowed()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                clearSelection()
                val messageResId = mapToneErrorToMessage(error)
                if (error !is DefaultToneImportException ||
                    error.reason == DefaultToneImportFailureReason.IMPORT_FAILED
                ) {
                    onError(error)
                }
                _events.trySend(DeviceDefaultTonesEvent.ShowErrorById(messageResId))
            }
        }
    }

    fun onCustomTonePicked(type: DeviceDefaultToneType, sourceUri: Uri) {
        savedState[PHASE] = "importing"
        viewModelScope.launch {
            showLoading()

            val importedTone = runCatching {
                withContext(dispatchers.io) {
                    toneManager.importCustomTone(type, sourceUri).getOrThrow()
                }
            }

            hideLoading()

            importedTone.onSuccess { importedCustomTone ->
                pendingToneSelection = PendingToneSelection(
                    toneType = type,
                    uri = importedCustomTone.uri,
                    cleanupImportedOnDiscard = importedCustomTone.wasNewlyCreated
                )
                pendingToneSelection?.let(::saveSelection)
                applyPendingSelectionIfAllowed()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                clearSelection()
                val messageResId = mapToneErrorToMessage(error)
                if (error !is DefaultToneImportException ||
                    error.reason == DefaultToneImportFailureReason.IMPORT_FAILED
                ) {
                    onError(error)
                }
                _events.trySend(DeviceDefaultTonesEvent.ShowErrorById(messageResId))
            }
        }
    }

    fun onWriteSettingsDialogConfirmed() {
        openedWriteSettingsForPendingSelection = true
        _events.trySend(
            DeviceDefaultTonesEvent.OpenIntent(
                toneManager.createManageWriteSettingsIntent()
            )
        )
    }

    fun onWriteSettingsDialogCancelled() {
        openedWriteSettingsForPendingSelection = false
        discardPendingSelection()
    }

    fun onReturnedFromWriteSettings() {
        if (toneManager.canWriteSystemSettings()) {
            applyPendingSelectionIfAllowed()
        } else if (openedWriteSettingsForPendingSelection) {
            discardPendingSelection()
        }
        openedWriteSettingsForPendingSelection = false
    }

    fun onOpenSoundSettingsRequested() {
        _events.trySend(
            DeviceDefaultTonesEvent.OpenIntent(
                toneManager.createSoundSettingsIntent()
            )
        )
    }

    private fun applyPendingSelectionIfAllowed() {
        val pending = pendingToneSelection ?: return

        if (!toneManager.canWriteSystemSettings()) {
            _events.trySend(DeviceDefaultTonesEvent.ShowWriteSettingsDialog)
            return
        }

        pendingToneSelection = null
        savedState[PHASE] = "applying"
        applyToneSelection(pending)
    }

    private fun discardPendingSelection() {
        val pending = pendingToneSelection ?: return
        pendingToneSelection = null
        clearSelection()
        if (!pending.cleanupImportedOnDiscard) return
        val uri = pending.uri ?: return

        viewModelScope.launch {
            withContext(dispatchers.io) {
                toneManager.deleteImportedTone(uri)
            }
        }
    }

    private fun applyToneSelection(selection: PendingToneSelection) {
        viewModelScope.launch {
            showLoading()

            val setResult = runCatching {
                withContext(dispatchers.io) {
                    toneManager.setDefaultTone(selection.toneType, selection.uri).getOrThrow()
                }
            }

            setResult.onSuccess {
                refreshToneNamesInternal()
                clearSelection()
                recordSuccess()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                clearSelection()
                onError(error)
                _events.trySend(DeviceDefaultTonesEvent.ShowApplyFailedDialog)
            }

            hideLoading()
        }
    }

    private fun refreshToneNames() {
        viewModelScope.launch {
            showLoading()
            refreshToneNamesInternal()
            hideLoading()
        }
    }

    private suspend fun refreshToneNamesInternal() {
        val names = runCatching {
            withContext(dispatchers.io) {
                Triple(
                    toneManager.getCurrentDisplayName(DeviceDefaultToneType.RINGTONE),
                    toneManager.getCurrentDisplayName(DeviceDefaultToneType.NOTIFICATION),
                    toneManager.getCurrentDisplayName(DeviceDefaultToneType.ALARM)
                )
            }
        }.onFailure {
                if (it is CancellationException) throw it
                onError(it)
            }
            .getOrNull()

        if (names == null) {
            _events.trySend(DeviceDefaultTonesEvent.ShowErrorById(R.string.something_went_wrong))
            return
        }

        _state.update {
            it.copy(
                ringtoneDisplayName = names.first,
                notificationDisplayName = names.second,
                alarmDisplayName = names.third
            )
        }
    }

    private fun showLoading() {
        _state.update { it.copy(isLoading = true) }
    }

    private fun hideLoading() {
        _state.update { it.copy(isLoading = false) }
    }

    private fun mapToneErrorToMessage(error: Throwable): Int {
        val reason = (error as? DefaultToneImportException)?.reason
        return when (reason) {
            DefaultToneImportFailureReason.INVALID_FORMAT -> R.string.ringtone_format_not_supported
            DefaultToneImportFailureReason.NOTIFICATION_TONE_TOO_LONG -> R.string.default_tone_notification_too_long
            DefaultToneImportFailureReason.NOTIFICATION_TONE_DURATION_UNKNOWN -> R.string.default_tone_notification_duration_unknown
            DefaultToneImportFailureReason.LEGACY_STORAGE_PERMISSION_REQUIRED -> R.string.need_permission_to_run
            DefaultToneImportFailureReason.IMPORT_FAILED -> R.string.default_tone_import_failed
            null -> R.string.default_tone_import_failed
        }
    }
    private companion object {
        const val SELECTION_TYPE = "pending_tone_type"
        const val SELECTION_URI = "pending_tone_uri"
        const val SELECTION_IMPORTED = "pending_tone_imported"
        const val PHASE = "pending_tone_phase"
        const val SETTINGS_OPENED = "pending_tone_settings_opened"
    }

}
