package com.milen.grounpringtonesetter.data.accounts

import android.content.Context
import android.provider.ContactsContract

internal class AccountsResolver(context: Context) {

    private val appContext = context.applicationContext

    /** Returns raw accounts as "type:name" (deduped). */
    fun getAccounts(): Set<String> {
        val accs = linkedSetOf<String>()
        val projection = arrayOf(
            ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContactsContract.RawContacts.ACCOUNT_NAME
        )
        appContext.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { c ->
            val idxType = c.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
            val idxName = c.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_NAME)
            while (c.moveToNext()) {
                val type = c.getString(idxType) ?: continue
                val name = c.getString(idxName) ?: continue
                if (type.isNotBlank() && name.isNotBlank()) {
                    accs.add("$type:$name")
                }
            }
        }
        return accs
    }

    companion object {
        /** "type:name" -> "name" for compact labels in UI. */
        fun labelOf(raw: String): String = raw.substringAfter(':', raw)
    }
}