package com.milen.grounpringtonesetter.data.accounts

import android.content.Context
import android.provider.ContactsContract

internal object AccountsResolver {

    /**
     * Returns a stable set like: "com.google:you@gmail.com" or "local_phone:Device"
     * We read from RawContacts to reflect actual accounts that have contacts.
     */
    fun getContactsAccounts(context: Context): Set<String> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContactsContract.RawContacts.ACCOUNT_NAME
        )
        val set = LinkedHashSet<String>()
        val sel = "${ContactsContract.RawContacts.DELETED}=0"
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            projection,
            sel,
            null,
            null
        )?.use { c ->
            val idxType = c.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
            val idxName = c.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)
            while (c.moveToNext()) {
                val type = c.getString(idxType) ?: "unknown"
                val name = c.getString(idxName) ?: "unknown"
                set.add("$type:$name")
            }
        }
        return set
    }
}