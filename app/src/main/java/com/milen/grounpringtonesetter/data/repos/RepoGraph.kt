package com.milen.grounpringtonesetter.data.repos

import android.app.Application
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.data.prefs.SelectedAccountsStore
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.Tracker

internal object RepoGraph {
    @Volatile
    private var repo: ContactsRepository? = null

    fun contacts(
        app: Application,
        helper: ContactsHelper,
        tracker: Tracker,
        prefs: EncryptedPreferencesHelper,
    ): ContactsRepository =
        repo ?: synchronized(this) {
            repo ?: ContactsRepositoryImpl(
                app = app,
                helper = helper,
                tracker = tracker,
                accountsProvider = { SelectedAccountsStore.read(prefs) } // <- NEW
            ).also { repo = it }
        }
}