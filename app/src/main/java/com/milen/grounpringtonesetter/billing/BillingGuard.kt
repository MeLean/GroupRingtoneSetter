// app/src/main/java/com/milen/grounpringtonesetter/billing/BillingGuard.kt
package com.milen.grounpringtonesetter.billing

import java.util.concurrent.atomic.AtomicBoolean

/** Marks that a *legit* billing flow is being launched by us right now. */
object BillingGuard {
    private val expecting = AtomicBoolean(false)
    fun beginLaunch() {
        expecting.set(true)
    }

    fun endLaunch() {
        expecting.set(false)
    }

    fun isExpecting(): Boolean = expecting.get()
}