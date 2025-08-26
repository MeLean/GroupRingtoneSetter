package com.milen.grounpringtonesetter.data.accounts

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
@JvmInline
value class AccountId(val raw: String) : Parcelable {
    val type: String get() = raw.substringBefore(':')
    val name: String get() = raw.substringAfter(':')

    override fun toString(): String = raw

    val label: String
        get() = "$name ($type)"

    companion object {
        fun of(type: String, name: String): AccountId = AccountId("$type:$name")
    }

}