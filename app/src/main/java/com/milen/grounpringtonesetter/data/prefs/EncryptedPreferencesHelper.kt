package com.milen.grounpringtonesetter.data.prefs

import android.app.Application
import com.milen.grounpringtonesetter.utils.Telemetry

internal class EncryptedPreferencesHelper private constructor(
    private val secure: SecurePreferences,
) {
    constructor(app: Application, tracker: Telemetry) : this(
        SecurePreferences(app, tracker).apply { initMigration() }
    )

    fun saveString(key: String, value: String) {
        secure.saveString(key, value)
    }

    fun getString(key: String, defaultValue: String? = null): String? {
        return secure.getString(key, defaultValue)
    }

    suspend fun saveStringAsync(key: String, value: String) = secure.putStringAsync(key, value)
    
    suspend fun getStringAsync(key: String, defaultValue: String? = null): String? =
        secure.getStringAsync(key, defaultValue)

    suspend fun removeAsync(key: String) = secure.removeAsync(key)
}
