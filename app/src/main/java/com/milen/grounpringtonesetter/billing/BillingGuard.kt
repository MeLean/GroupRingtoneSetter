package com.milen.grounpringtonesetter.billing

import android.content.Intent
import android.os.Bundle
import java.util.concurrent.atomic.AtomicLong

object BillingGuard {
    private const val LAUNCH_WINDOW_MS = 10000L
    private val lastLaunchTime = AtomicLong(0L)

    fun beginLaunch() {
        lastLaunchTime.set(System.currentTimeMillis())
    }

    fun endLaunch() {
        lastLaunchTime.set(0L)
    }

    fun isExpecting(): Boolean {
        val launched = lastLaunchTime.get()
        if (launched == 0L) return false
        return (System.currentTimeMillis() - launched) < LAUNCH_WINDOW_MS
    }

    fun hasValidBillingExtras(intent: Intent?): Boolean {
        if (intent == null) return false
        val extras = intent.extras ?: return false
        if (extras.isEmpty) return false
        return hasKnownBillingKey(extras)
    }

    private fun hasKnownBillingKey(extras: Bundle): Boolean {
        return extras.containsKey("BUY_INTENT") ||
                extras.containsKey("SUBS_MANAGEMENT_INTENT") ||
                extras.containsKey("IN_APP_MESSAGE_INTENT") ||
                extras.containsKey("result_receiver")
    }
}