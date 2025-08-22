package com.milen.grounpringtonesetter.data.accounts

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract

internal class AccountsResolver(context: Context) {
    private val app = context.applicationContext

    /** Returns accounts as "type:name", deduped. */
    fun getAccounts(): Set<String> {
        // 1) Which account TYPES can own contacts on this device
        val contactCapableTypes: Set<String> =
            ContentResolver.getSyncAdapterTypes()
                .asSequence()
                .filter { it.authority == ContactsContract.AUTHORITY }
                .map { it.accountType }
                .toSet()

        // 2) All accounts visible to this app, filtered to contact-capable types
        val am = AccountManager.get(app)
        return am.accounts
            .asSequence()
            .filter { it.type in contactCapableTypes }
            .map { "${it.type}:${it.name}" }
            .toCollection(linkedSetOf())
    }

    companion object {
        fun labelOf(raw: String): String = raw.substringAfter(':', raw)
    }
}