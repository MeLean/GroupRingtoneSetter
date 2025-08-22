package com.milen.grounpringtonesetter.data.accounts

@JvmInline
value class AccountId(val raw: String) {
    val type: String get() = raw.substringBefore(':')
    val name: String get() = raw.substringAfter(':')

    override fun toString(): String = raw
}