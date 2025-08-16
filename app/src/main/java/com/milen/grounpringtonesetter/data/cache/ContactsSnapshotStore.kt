package com.milen.grounpringtonesetter.data.cache

import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import org.json.JSONArray
import org.json.JSONObject

internal object ContactsSnapshotStore {
    private const val SNAP_PREFIX = "labels_snapshot_"
    private const val TS_PREFIX = "labels_snapshot_ts_"

    fun write(
        prefs: EncryptedPreferencesHelper,
        accountsKey: String,
        items: List<LabelItem>,
        ts: Long,
    ) {
        val arr = JSONArray()
        items.forEach { label ->
            val obj = JSONObject().apply {
                put("id", label.id)
                put("groupName", label.groupName)
                put("ringtoneFileName", label.ringtoneFileName)
                put("ringtoneUriList", JSONArray(label.ringtoneUriList))

                val contactsArr = JSONArray()
                label.contacts.forEach { c ->
                    contactsArr.put(
                        JSONObject().apply {
                            put("id", c.id)
                            put("name", c.name)
                            put("phone", c.phone)
                            put("ringtoneUriStr", c.ringtoneUriStr)
                        }
                    )
                }
                put("contacts", contactsArr)
            }
            arr.put(obj)
        }

        prefs.saveString(SNAP_PREFIX + accountsKey, arr.toString())
        prefs.saveString(TS_PREFIX + accountsKey, ts.toString())
    }

    fun read(prefs: EncryptedPreferencesHelper, accountsKey: String): Pair<List<LabelItem>, Long>? {
        val json = prefs.getString(SNAP_PREFIX + accountsKey) ?: return null
        val ts = prefs.getString(TS_PREFIX + accountsKey)?.toLongOrNull() ?: 0L

        val parsed = mutableListOf<LabelItem>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val contactsJson = o.optJSONArray("contacts") ?: JSONArray()
            val contacts = buildList {
                for (j in 0 until contactsJson.length()) {
                    val cj = contactsJson.getJSONObject(j)
                    add(
                        Contact(
                            id = cj.optLong("id"),
                            name = cj.optString("name"),
                            phone = if (cj.isNull("phone")) null else cj.optString("phone"),
                            ringtoneUriStr = if (cj.isNull("ringtoneUriStr")) null else cj.optString(
                                "ringtoneUriStr"
                            ),
                        )
                    )
                }
            }
            val ringtoneUrisJson = o.optJSONArray("ringtoneUriList") ?: JSONArray()
            val ringtoneUris = buildList {
                for (k in 0 until ringtoneUrisJson.length()) add(ringtoneUrisJson.getString(k))
            }

            parsed.add(
                LabelItem(
                    id = o.optLong("id"),
                    groupName = o.optString("groupName"),
                    contacts = contacts,
                    ringtoneUriList = ringtoneUris,
                    ringtoneFileName = o.optString("ringtoneFileName"),
                )
            )
        }
        return parsed to ts
    }
}
