package com.milen.grounpringtonesetter.data.repos

import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.data.accounts.AccountRepository
import com.milen.grounpringtonesetter.data.accounts.AccountRepositoryImpl
import com.milen.grounpringtonesetter.data.accounts.AccountsResolver
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.utils.ContactsHelper


internal object RepoGraph {
    @Volatile
    private var repo: ContactsRepository? = null

    @Volatile
    private var accRepo: AccountRepository? = null

    fun accountRepo(
        app: App,
        prefs: EncryptedPreferencesHelper,
    ): AccountRepository = accRepo ?: synchronized(this) {
        accRepo ?: AccountRepositoryImpl(
            prefs = prefs,
            resolver = AccountsResolver(app)
        ).also { accRepo = it }
    }

    fun contactsRepo(
        app: App,
        helper: ContactsHelper,
        prefs: EncryptedPreferencesHelper,
    ): ContactsRepository =
        repo ?: synchronized(this) {
            val ar = accountRepo(app, prefs)
            repo ?: ContactsRepositoryImpl(
                app = app,
                helper = helper,
                tracker = app.tracker,
                prefs = prefs,
                accountsProvider = { ar.selected.value }
            ).also { repo = it }
        }
}
