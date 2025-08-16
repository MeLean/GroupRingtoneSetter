package com.milen.grounpringtonesetter.data.prefs

import android.app.Application

internal class EncryptedPreferencesHelper private constructor(
    private val secure: SecurePreferences,
) {
    constructor(app: Application) : this(
        SecurePreferences(app).apply { initMigration() }
    )

    fun saveString(key: String, value: String) {
        secure.saveString(key, value)
    }

    fun getString(key: String, defaultValue: String? = null): String? {
        return secure.getString(key, defaultValue)
    }
}