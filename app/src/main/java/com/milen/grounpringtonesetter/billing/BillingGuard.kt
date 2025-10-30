package com.milen.grounpringtonesetter.billing

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
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
        return hasKnownBillingKey(extras) && hasPendingIntent(extras)
    }

    private fun hasKnownBillingKey(extras: Bundle): Boolean {
        return extras.containsKey("BUY_INTENT") ||
                extras.containsKey("SUBS_MANAGEMENT_INTENT") ||
                extras.containsKey("IN_APP_MESSAGE_INTENT") ||
                extras.containsKey("result_receiver")
    }

    private inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelable(key)
        }
    }

    private fun Bundle.pendingIntent(key: String): PendingIntent? = parcelable(key)

    private fun hasPendingIntent(extras: Bundle): Boolean {
        return try {
            val buyIntent: PendingIntent? = extras.pendingIntent("BUY_INTENT")
            val subsIntent: PendingIntent? = extras.pendingIntent("SUBS_MANAGEMENT_INTENT")
            val messageIntent: PendingIntent? = extras.pendingIntent("IN_APP_MESSAGE_INTENT")
            
            buyIntent != null || subsIntent != null || messageIntent != null || extras.containsKey("result_receiver")
        } catch (_: Throwable) {
            false
        }
    }
}