package com.milen.grounpringtonesetter.data.prefs

import kotlin.coroutines.cancellation.CancellationException

internal fun resolveSecurePreferenceValue(
    encryptedValue: String?,
    migrationComplete: Boolean,
    defaultValue: String?,
    decrypt: (String) -> String?,
    readLegacy: () -> String?,
): String? = when {
    encryptedValue != null -> decrypt(encryptedValue) ?: defaultValue
    migrationComplete -> defaultValue
    else -> readLegacy()
}

internal fun readLegacyPreferencesForMigration(
    readLegacy: () -> Map<String, *>,
    onFailure: (Exception) -> Unit,
): Map<String, *> = try {
    readLegacy()
} catch (error: Exception) {
    if (error is CancellationException) throw error
    onFailure(error)
    emptyMap<String, Any?>()
}
