package com.milen.grounpringtonesetter.ui.defaulttones

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import com.milen.grounpringtonesetter.ui.RingtoneCompletion

internal data class DeviceDefaultTonesState(
    val isLoading: Boolean = true,
    val ringtoneDisplayName: String = "",
    val notificationDisplayName: String = "",
    val alarmDisplayName: String = "",
    val pendingAdCompletion: RingtoneCompletion? = null,
)

internal enum class TonePickerKind { SYSTEM, FILE, LEGACY_PERMISSION }

internal data class TonePickerLaunchConfig(
    val toneType: DeviceDefaultToneType,
    val existingUri: Uri?,
)

internal sealed interface DeviceDefaultTonesEvent {
    data class LaunchTonePicker(val config: TonePickerLaunchConfig) : DeviceDefaultTonesEvent
    data object ShowWriteSettingsDialog : DeviceDefaultTonesEvent
    data object ShowApplyFailedDialog : DeviceDefaultTonesEvent
    data class OpenIntent(val intent: Intent) : DeviceDefaultTonesEvent
    data class ShowErrorById(@param:StringRes val messageResId: Int) : DeviceDefaultTonesEvent
}
