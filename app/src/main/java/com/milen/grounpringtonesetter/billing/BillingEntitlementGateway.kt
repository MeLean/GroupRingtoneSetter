package com.milen.grounpringtonesetter.billing

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/** App-facing billing contract. Google Play Billing remains an implementation detail. */
internal interface BillingEntitlementGateway {
    val state: StateFlow<EntitlementState>

    suspend fun start()

    suspend fun launchPurchase(activity: Activity): Int

    fun end()
}
