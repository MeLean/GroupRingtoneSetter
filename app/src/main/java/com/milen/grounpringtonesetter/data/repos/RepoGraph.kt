package com.milen.grounpringtonesetter.data.repos

import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.data.accounts.AccountsResolver
import com.milen.grounpringtonesetter.data.local.EncryptedLocalLabelsDataSource
import com.milen.grounpringtonesetter.data.local.LocalContactLabelMirror
import com.milen.grounpringtonesetter.data.local.LocalLabelsStore
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.data.sources.ContactSourceRepository
import com.milen.grounpringtonesetter.data.sources.ContactSourceRepositoryImpl
import com.milen.grounpringtonesetter.utils.ContactsHelper


internal object RepoGraph {
    @Volatile
    private var repo: ContactsRepository? = null

    @Volatile
    private var sourceRepo: ContactSourceRepository? = null

    fun contactSourceRepo(
        app: App,
        helper: ContactsHelper,
        prefs: EncryptedPreferencesHelper,
    ): ContactSourceRepository = sourceRepo ?: synchronized(this) {
        sourceRepo ?: ContactSourceRepositoryImpl(
            prefs = prefs,
            resolver = AccountsResolver(app),
            contactsHelper = helper
        ).also { sourceRepo = it }
    }

    fun contactsRepo(
        app: App,
        helper: ContactsHelper,
        prefs: EncryptedPreferencesHelper,
    ): ContactsRepository =
        repo ?: synchronized(this) {
            val sr = contactSourceRepo(app, helper, prefs)
            repo ?: ContactsRepositoryImpl(
                app = app,
                helper = helper,
                tracker = app.tracker,
                prefs = prefs,
                localLabelsStore = LocalLabelsStore(
                    dataSource = EncryptedLocalLabelsDataSource(prefs)
                ),
                localLabelMirror = LocalContactLabelMirror(
                    appContext = app,
                    tracker = app.tracker
                ),
                sourceProvider = { sr.selected.value }
            ).also { repo = it }
        }
}
