package com.milen.grounpringtonesetter.billing

import android.app.Activity
import android.os.Bundle
import com.milen.grounpringtonesetter.App

class BillingUnavailableActivity : Activity() {
    private var blockReason = BillingProxyBlockReason.MALFORMED_INTENT
    private var compatible = false
    private var intentStatus = BillingProxyIntentStatus.MISSING

    internal fun configure(
        reason: BillingProxyBlockReason,
        platformCompatible: Boolean,
        status: BillingProxyIntentStatus,
    ) {
        blockReason = reason
        compatible = platformCompatible
        intentStatus = status
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runCatching {
            (application as? App)?.tracker?.trackEvent(
                "billing_proxy_blocked",
                mapOf(
                    "activity_class" to PROXY_BILLING_ACTIVITY_CLASS,
                    "reason" to blockReason.logValue,
                    "platform_compatible" to compatible,
                    "intent_status" to intentStatus.name,
                ),
            )
        }

        setResult(RESULT_CANCELED)
        finish()
    }
}
