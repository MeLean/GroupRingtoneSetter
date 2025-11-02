package com.milen.grounpringtonesetter.billing

import android.app.Activity
import android.os.Bundle

/**
 * Transparent no-op activity that immediately returns RESULT_CANCELED.
 * Used only when we must satisfy ProxyBillingActivity's required PendingIntent
 * for rogue launches (pre-launch bots, fuzzers).
 */
class NoopBillingResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        finish()
    }
}