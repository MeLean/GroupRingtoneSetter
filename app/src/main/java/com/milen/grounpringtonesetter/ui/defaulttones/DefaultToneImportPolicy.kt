package com.milen.grounpringtonesetter.ui.defaulttones

internal enum class DefaultToneImportFailureReason {
    INVALID_FORMAT,
    NOTIFICATION_TONE_TOO_LONG,
    NOTIFICATION_TONE_DURATION_UNKNOWN,
    LEGACY_STORAGE_PERMISSION_REQUIRED,
    IMPORT_FAILED,
}

internal class DefaultToneImportException(
    val reason: DefaultToneImportFailureReason,
    cause: Throwable? = null,
) : RuntimeException(reason.name, cause)

internal object NotificationToneDurationPolicy {
    const val MAX_NOTIFICATION_DURATION_MS: Long = 10_000L

    fun getFailureReason(durationMs: Long?): DefaultToneImportFailureReason? {
        if (durationMs == null) return DefaultToneImportFailureReason.NOTIFICATION_TONE_DURATION_UNKNOWN
        if (durationMs > MAX_NOTIFICATION_DURATION_MS) {
            return DefaultToneImportFailureReason.NOTIFICATION_TONE_TOO_LONG
        }
        return null
    }
}
