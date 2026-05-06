package com.milen.grounpringtonesetter.data.sources

import android.content.Context
import android.os.Parcelable
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.accounts.AccountId
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Parcelize
internal sealed class ContactSource : Parcelable {
    abstract val stableKey: String

    fun displayLabel(context: Context): String = when (this) {
        is CloudAccount -> account.label
        OnDevice -> context.getString(R.string.contact_source_on_device)
    }

    @Parcelize
    data class CloudAccount(val account: AccountId) : ContactSource() {
        @IgnoredOnParcel
        override val stableKey: String = "account:${account.raw}"
    }

    @Parcelize
    data object OnDevice : ContactSource() {
        @IgnoredOnParcel
        override val stableKey: String = "on_device"
    }

    companion object {
        fun fromStored(raw: String): ContactSource? {
            return when {
                raw == OnDevice.stableKey -> OnDevice
                raw.startsWith("account:") -> CloudAccount(AccountId(raw.removePrefix("account:")))
                else -> null
            }
        }
    }
}
