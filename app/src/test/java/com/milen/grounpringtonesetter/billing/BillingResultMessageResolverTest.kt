package com.milen.grounpringtonesetter.billing

import com.android.billingclient.api.BillingClient
import com.milen.grounpringtonesetter.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillingResultMessageResolverTest {

    @Test
    fun `already owned does not surface a purchase error message`() {
        val messageResId = BillingResultMessageResolver.resolveMessageResId(
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
        )

        assertNull(messageResId)
    }

    @Test
    fun `item unavailable maps to product not found message`() {
        val messageResId = BillingResultMessageResolver.resolveMessageResId(
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE
        )

        assertEquals(R.string.billing_product_not_found, messageResId)
    }

    @Test
    fun `every billing result category maps to a stable user outcome`() {
        val expectations = mapOf(
            BillingClient.BillingResponseCode.OK to null,
            BillingClient.BillingResponseCode.USER_CANCELED to null,
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED to null,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR to R.string.billing_service_unavailable,
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE to R.string.billing_product_not_found,
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE to R.string.billing_configuration_error,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED to R.string.billing_connection_timeout,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE to R.string.billing_temporary_unavailable,
            BillingClient.BillingResponseCode.ERROR to R.string.purchase_unavailable,
            Int.MIN_VALUE to R.string.purchase_unavailable,
        )

        expectations.forEach { (responseCode, expectedMessage) ->
            assertEquals(
                "Unexpected message for billing response $responseCode",
                expectedMessage,
                BillingResultMessageResolver.resolveMessageResId(responseCode),
            )
        }
    }
}
