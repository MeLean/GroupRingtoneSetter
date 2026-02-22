package com.milen.grounpringtonesetter.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult

sealed class BillingError(
    val responseCode: Int,
    val debugMessage: String?,
    val category: ErrorCategory
) {
    enum class ErrorCategory {
        NONE,
        CONFIGURATION,
        TEMPORARY,
        FATAL
    }

    data class NonError(
        val code: Int,
        val message: String?
    ) : BillingError(code, message, ErrorCategory.NONE)

    data class ConfigurationError(
        val code: Int,
        val message: String?
    ) : BillingError(code, message, ErrorCategory.CONFIGURATION)

    data class TemporaryError(
        val code: Int,
        val message: String?
    ) : BillingError(code, message, ErrorCategory.TEMPORARY)

    data class FatalError(
        val code: Int,
        val message: String?
    ) : BillingError(code, message, ErrorCategory.FATAL)

    companion object {
        fun fromBillingResult(result: BillingResult): BillingError {
            return when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> NonError(
                    result.responseCode,
                    result.debugMessage
                )
                BillingClient.BillingResponseCode.USER_CANCELED -> NonError(
                    result.responseCode,
                    result.debugMessage
                )
                BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> ConfigurationError(
                    result.responseCode,
                    result.debugMessage
                )
                BillingClient.BillingResponseCode.DEVELOPER_ERROR -> ConfigurationError(
                    result.responseCode,
                    result.debugMessage
                )
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> ConfigurationError(
                    result.responseCode,
                    result.debugMessage
                )
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> TemporaryError(
                    result.responseCode,
                    result.debugMessage
                )
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> TemporaryError(
                    result.responseCode,
                    result.debugMessage
                )
                BillingClient.BillingResponseCode.ERROR -> FatalError(
                    result.responseCode,
                    result.debugMessage
                )
                else -> FatalError(
                    result.responseCode,
                    result.debugMessage
                )
            }
        }

        fun fromResponseCode(code: Int, message: String? = null): BillingError {
            return when (code) {
                BillingClient.BillingResponseCode.OK -> NonError(code, message)
                BillingClient.BillingResponseCode.USER_CANCELED -> NonError(code, message)
                BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> ConfigurationError(code, message)
                BillingClient.BillingResponseCode.DEVELOPER_ERROR -> ConfigurationError(code, message)
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> ConfigurationError(code, message)
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> TemporaryError(code, message)
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> TemporaryError(code, message)
                BillingClient.BillingResponseCode.ERROR -> FatalError(code, message)
                else -> FatalError(code, message)
            }
        }
    }
}
