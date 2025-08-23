package com.milen.grounpringtonesetter.data.prefs

import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.accounts.AccountId
import org.json.JSONArray
import org.json.JSONObject

internal object ContactsCacheStore {
    private const val KEY_PREFIX = "contacts_cache_v1"

    fun keyFor(accountId: AccountId?): String =
        if (accountId == null) "$KEY_PREFIX:ALL" else "$KEY_PREFIX:${accountId.type}:${accountId.name}"

    fun read(
        prefs: EncryptedPreferencesHelper,
        accountId: AccountId?,
        default: List<Contact>? = null,
    ): List<Contact>? {
        val key = keyFor(accountId)
        val json = prefs.getString(key) ?: return default
        return runCatching { fromJson(json) }.getOrDefault(default)
    }

    fun write(prefs: EncryptedPreferencesHelper, accountId: AccountId?, contacts: List<Contact>) {
        val key = keyFor(accountId)
        prefs.saveString(key, toJson(contacts))
    }

    fun clear(prefs: EncryptedPreferencesHelper, accountId: AccountId?) {
        val key = keyFor(accountId)
        prefs.saveString(key, "")
    }


    private fun toJson(list: List<Contact>): String {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
                .put("id", c.id)
                .put("name", c.name)
                .put("phone", c.phone) // null -> JSONObject.NULL
                .put("ringtone", c.ringtoneUriStr)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun fromJson(json: String): List<Contact> {
        val arr = JSONArray(json)
        val out = ArrayList<Contact>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val phone = o.optString("phone").nullIfBlank()
            val ringtone = o.optString("ringtone").nullIfBlank()
            out.add(
                Contact(
                    id = o.optLong("id"),
                    name = o.optString("name", ""),
                    phone = phone,
                    ringtoneUriStr = ringtone
                )
            )
        }
        return out
    }

    private fun String?.nullIfBlank(): String? =
        if (this == null || this.isBlank()) null else this
}