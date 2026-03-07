package com.milen.grounpringtonesetter.ui.defaulttones.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.ui.defaulttones.DefaultToneImportException
import com.milen.grounpringtonesetter.ui.defaulttones.DefaultToneImportFailureReason
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneManager
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneType
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultTonesEvent
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultTonesState
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

internal class DeviceDefaultTonesViewModel(
    private val onError: (Throwable) -> Unit,
    private val toneManager: DeviceDefaultToneManager,
    private val entitlementState: StateFlow<EntitlementState>,
    private val dispatchers: DispatcherProvider = DispatchersProvider,
) : ViewModel() {

    private data class PendingToneSelection(
        val toneType: DeviceDefaultToneType,
        val uri: Uri?,
        val cleanupImportedOnDiscard: Boolean,
    )

    private val _state = MutableStateFlow(DeviceDefaultTonesState())
    val state: StateFlow<DeviceDefaultTonesState> = _state

    private val _events = Channel<DeviceDefaultTonesEvent>(Channel.BUFFERED)
    val events: Flow<DeviceDefaultTonesEvent> = _events.receiveAsFlow()

    private var pendingToneSelection: PendingToneSelection? = null
    private var openedWriteSettingsForPendingSelection: Boolean = false

    init {
        refreshToneNames()
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
            }.onFailure { onError(it) }
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
                applyPendingSelectionIfAllowed()
            }.onFailure { error ->
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
                applyPendingSelectionIfAllowed()
            }.onFailure { error ->
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
        applyToneSelection(pending)
    }

    private fun discardPendingSelection() {
        val pending = pendingToneSelection ?: return
        pendingToneSelection = null
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
                if (entitlementState.value == EntitlementState.NOT_OWNED) {
                    _events.trySend(DeviceDefaultTonesEvent.ShowInterstitialAd)
                }
            }.onFailure { error ->
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
        }.onFailure { onError(it) }
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
}
