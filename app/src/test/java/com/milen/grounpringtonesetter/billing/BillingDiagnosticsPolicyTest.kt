package com.milen.grounpringtonesetter.billing

import com.android.billingclient.api.BillingClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingDiagnosticsPolicyTest {

    @Test
    fun `launch response records billing unavailable as error because billing ui failed to start`() {
        val result = BillingDiagnosticsPolicy.shouldRecordLaunchResponseError(
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
        )

        assertTrue(result)
    }

    @Test
    fun `launch response records fatal error code as error`() {
        val result = BillingDiagnosticsPolicy.shouldRecordLaunchResponseError(
            BillingClient.BillingResponseCode.ERROR
        )

        assertTrue(result)
    }

    @Test
    fun `launch response does not record item already owned as error`() {
        val result = BillingDiagnosticsPolicy.shouldRecordLaunchResponseError(
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
        )

        assertFalse(result)
    }

    @Test
    fun `purchases updated does not record ok with empty purchases as error`() {
        val result = BillingDiagnosticsPolicy.shouldRecordPurchasesUpdatedError(
            responseCode = BillingClient.BillingResponseCode.OK,
            hasPurchases = false
        )

        assertFalse(result)
    }

    @Test
    fun `purchases updated does not record billing unavailable as error`() {
        val result = BillingDiagnosticsPolicy.shouldRecordPurchasesUpdatedError(
            responseCode = BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            hasPurchases = false
        )

        assertFalse(result)
    }

    @Test
    fun `purchases updated does not record item already owned as error`() {
        val result = BillingDiagnosticsPolicy.shouldRecordPurchasesUpdatedError(
            responseCode = BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED,
            hasPurchases = false,
        )

        assertFalse(result)
    }

    @Test
    fun `purchases updated records fatal error as error`() {
        val result = BillingDiagnosticsPolicy.shouldRecordPurchasesUpdatedError(
            responseCode = BillingClient.BillingResponseCode.ERROR,
            hasPurchases = false
        )

        assertTrue(result)
    }
}
