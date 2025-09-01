package com.milen.grounpringtonesetter

import android.app.Application
import com.milen.grounpringtonesetter.billing.BillingEntitlementManager
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import com.milen.grounpringtonesetter.utils.Tracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


class App : Application() {
    internal val tracker: Tracker by lazy { Tracker() }
    internal lateinit var billingManager: BillingEntitlementManager
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectNetwork()
                    .penaltyLog()
                    .build()
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }


        billingManager = BillingEntitlementManager(this, tracker)
        CoroutineScope(SupervisorJob() + DispatchersProvider.io).launch {
            runCatching {
                billingManager.start()
            }.onFailure {
                tracker.trackError(it)
            }
        }
    }
}