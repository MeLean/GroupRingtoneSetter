package com.milen.grounpringtonesetter.billing

import android.os.Bundle
import com.android.billingclient.api.ProxyBillingActivity
import com.milen.grounpringtonesetter.App

class SafeProxyBillingActivity : ProxyBillingActivity() {

    private var shouldFinish = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val tracker = (application as? App)?.tracker

        tracker?.trackEvent(
            "proxy_billing_activity_created", mapOf(
                "has_intent" to (intent != null),
                "has_extras" to (intent?.extras != null),
                "extras_empty" to (intent?.extras?.isEmpty ?: true),
                "is_expecting" to BillingGuard.isExpecting(),
                "has_valid_extras" to BillingGuard.hasValidBillingExtras(intent)
            )
        )

        if (!BillingGuard.isExpecting() || !BillingGuard.hasValidBillingExtras(intent)) {
            tracker?.trackEvent(
                "proxy_billing_activity_rejected", mapOf(
                    "reason" to "guard_validation_failed"
                )
            )
            shouldFinish = true
        }

        try {
            super.onCreate(savedInstanceState)
        } catch (e: Exception) {
            tracker?.trackEvent(
                "proxy_billing_activity_crash_prevented", mapOf(
                    "error" to (e.message ?: "unknown"),
                    "type" to e.javaClass.simpleName
                )
            )
            shouldFinish = true
        }

        if (shouldFinish) {
            finish()
        }
    }

    override fun onDestroy() {
        BillingGuard.endLaunch()
        super.onDestroy()
    }
}