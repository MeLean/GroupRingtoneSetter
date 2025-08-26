package com.milen.grounpringtonesetter.data.accounts

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract

internal class AccountsResolver(context: Context) {
    private val app = context.applicationContext

    /** Returns accounts as AccountId("type:name"), deduped. */
    fun getAccounts(): Set<AccountId> {
        val contactCapableTypes: Set<String> =
            ContentResolver.getSyncAdapterTypes()
                .asSequence()
                .filter { it.authority == ContactsContract.AUTHORITY }
                .map { it.accountType }
                .toSet()

        val am = AccountManager.get(app)
        return am.accounts
            .asSequence()
            .filter { it.type in contactCapableTypes }
            .map { AccountId.of(it.type, it.name) }
            .toCollection(linkedSetOf())
    }
}