package com.milen.grounpringtonesetter

import android.app.Application
import com.milen.billing.BillingEntitlementManager
import com.milen.grounpringtonesetter.utils.Tracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


class App : Application() {
    internal val tracker: Tracker by lazy { Tracker() }
    internal lateinit var billingManager: BillingEntitlementManager
        private set

    override fun onCreate() {
        super.onCreate()
        billingManager = BillingEntitlementManager(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                billingManager.start()
            }.onFailure {
                tracker.trackError(it)
            }
        }
    }
}