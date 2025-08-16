// ✨ NEW — main/java/com/milen/grounpringtonesetter/data/prefs/SelectedAccountsStore.kt
package com.milen.grounpringtonesetter.data.prefs

internal object SelectedAccountsStore {
    private const val KEY_SELECTED_ACCOUNTS = "selected_accounts_v1"
    
    fun read(prefs: EncryptedPreferencesHelper): Set<String> {
        val raw = prefs.getString(KEY_SELECTED_ACCOUNTS) ?: return emptySet()
        return raw.split("|").filter { it.isNotBlank() }.toSet()
    }

    fun write(prefs: EncryptedPreferencesHelper, accounts: Set<String>) {
        val normalized = accounts.map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        prefs.saveString(KEY_SELECTED_ACCOUNTS, normalized.joinToString("|"))
    }

    fun clear(prefs: EncryptedPreferencesHelper) {
        prefs.saveString(KEY_SELECTED_ACCOUNTS, "")
    }

    fun hasSelection(prefs: EncryptedPreferencesHelper): Boolean =
        read(prefs).isNotEmpty()

    /**
     * Stable key for snapshot/cache bucketing.
     * If nothing selected yet, we fall back to "ALL" to preserve current behavior.
     */
    fun accountsKeyOrAll(prefs: EncryptedPreferencesHelper): String {
        val set = read(prefs)
        return if (set.isEmpty()) "ALL" else set.sorted().joinToString("|")
    }
}
