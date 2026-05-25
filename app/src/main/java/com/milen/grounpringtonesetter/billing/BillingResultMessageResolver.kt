package com.milen.grounpringtonesetter.billing

import androidx.annotation.StringRes
import com.android.billingclient.api.BillingClient
import com.milen.grounpringtonesetter.R

internal object BillingResultMessageResolver {

    @StringRes
    fun resolveMessageResId(responseCode: Int): Int? {
        val billingError = BillingError.fromResponseCode(responseCode)
        return when {
            responseCode == BillingClient.BillingResponseCode.OK -> null
            responseCode == BillingClient.BillingResponseCode.USER_CANCELED -> null
            responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> null
            billingError.category == BillingError.ErrorCategory.CONFIGURATION -> when (responseCode) {
                BillingClient.BillingResponseCode.DEVELOPER_ERROR -> R.string.billing_service_unavailable
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> R.string.billing_product_not_found
                BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> R.string.billing_configuration_error
                else -> R.string.billing_configuration_error
            }

            billingError.category == BillingError.ErrorCategory.TEMPORARY -> when (responseCode) {
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> R.string.billing_connection_timeout
                else -> R.string.billing_temporary_unavailable
            }

            else -> R.string.purchase_unavailable
        }
    }
}
