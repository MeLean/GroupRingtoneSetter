package com.milen.grounpringtonesetter.utils

import android.content.Intent
import android.os.Build
import android.os.Parcelable
import androidx.activity.result.ActivityResultLauncher

internal fun <T : Parcelable> Intent.getParcelableExtraCompat(key: String, type: Class<T>): T? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, type)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    } catch (_: Exception) {
        // Fallback for Samsung Android 13 NPE and other framework bugs
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
}

internal fun ActivityResultLauncher<Intent>.launchRingtonePickerSafely(
    intent: Intent,
    tracker: Telemetry,
    onSecurityError: () -> Unit
) {
    try {
        launch(intent)
    } catch (e: SecurityException) {
        tracker.trackError(e)
        onSecurityError()
    }
}
