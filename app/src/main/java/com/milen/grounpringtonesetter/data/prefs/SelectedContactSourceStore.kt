package com.milen.grounpringtonesetter.data.prefs

import com.milen.grounpringtonesetter.data.accounts.AccountId
import com.milen.grounpringtonesetter.data.sources.ContactSource

internal object SelectedContactSourceStore {
    private const val KEY_SELECTED_CONTACT_SOURCE = "selected_contact_source_v1"

    fun read(prefs: EncryptedPreferencesHelper): ContactSource? {
        val raw = prefs.getString(KEY_SELECTED_CONTACT_SOURCE)?.trim().orEmpty()
        if (raw.isBlank()) {
            val legacy = SelectedAccountsStore.read(prefs).firstOrNull()
            return legacy?.let { ContactSource.CloudAccount(AccountId(it)) }
        }
        return ContactSource.fromStored(raw)
    }

    fun sourceKeyOrAll(prefs: EncryptedPreferencesHelper): String =
        read(prefs)?.stableKey ?: "ALL"

    suspend fun writeAsync(
        prefs: EncryptedPreferencesHelper,
        source: ContactSource,
    ) {
        prefs.saveStringAsync(KEY_SELECTED_CONTACT_SOURCE, source.stableKey)
    }

    suspend fun clearAsync(prefs: EncryptedPreferencesHelper) {
        prefs.removeAsync(KEY_SELECTED_CONTACT_SOURCE)
    }
}
