package com.milen.grounpringtonesetter.ui.defaulttones

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes

internal data class DeviceDefaultTonesState(
    val isLoading: Boolean = true,
    val ringtoneDisplayName: String = "",
    val notificationDisplayName: String = "",
    val alarmDisplayName: String = "",
)

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
    data object ShowInterstitialAd : DeviceDefaultTonesEvent
}
