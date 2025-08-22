package com.milen.grounpringtonesetter.data.repos

import android.app.Application
import com.milen.grounpringtonesetter.data.accounts.AccountRepository
import com.milen.grounpringtonesetter.data.accounts.AccountRepositoryImpl
import com.milen.grounpringtonesetter.data.accounts.AccountsResolver
import com.milen.grounpringtonesetter.data.accounts.selectedSetOrEmpty
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.utils.ContactsHelper


internal object RepoGraph {
    @Volatile
    private var repo: ContactsRepository? = null

    @Volatile
    private var accRepo: AccountRepository? = null

    fun accountRepo(
        app: Application,
        prefs: EncryptedPreferencesHelper,
    ): AccountRepository = accRepo ?: synchronized(this) {
        accRepo ?: AccountRepositoryImpl(
            prefs = prefs,
            resolver = AccountsResolver(app)
        ).also { accRepo = it }
    }

    fun contacts(
        app: Application,
        helper: ContactsHelper,
        prefs: EncryptedPreferencesHelper,
    ): ContactsRepository =
        repo ?: synchronized(this) {
            val ar = accountRepo(app, prefs)
            repo ?: ContactsRepositoryImpl(
                helper = helper,
                accountsProvider = { ar.selectedSetOrEmpty() }
            ).also { repo = it }
        }
}
