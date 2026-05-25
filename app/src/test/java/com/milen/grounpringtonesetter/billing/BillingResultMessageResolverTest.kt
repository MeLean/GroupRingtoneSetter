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
}
