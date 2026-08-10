package com.milen.grounpringtonesetter.billing

import com.android.billingclient.api.BillingClient

internal object BillingDiagnosticsPolicy {

    fun shouldRecordLaunchResponseError(responseCode: Int): Boolean {
        if (responseCode == BillingClient.BillingResponseCode.OK ||
            responseCode == BillingClient.BillingResponseCode.USER_CANCELED ||
            responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
        ) {
            return false
        }

        return true
    }

    fun shouldRecordPurchasesUpdatedError(
        responseCode: Int,
        hasPurchases: Boolean,
    ): Boolean {
        if (responseCode == BillingClient.BillingResponseCode.OK && !hasPurchases) {
            return false
        }

        if (responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            return false
        }

        if (responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            return false
        }

        return BillingError.fromResponseCode(responseCode).category ==
                BillingError.ErrorCategory.FATAL
    }
}
