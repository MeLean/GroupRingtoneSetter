package com.milen.grounpringtonesetter

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import com.milen.grounpringtonesetter.billing.BillingEntitlementManager
import com.milen.grounpringtonesetter.billing.BillingGuard
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

        // 🔒 Kill bogus ProxyBillingActivity launches (bots/PLR) BEFORE its onCreate.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(a: Activity, s: Bundle?) {
                killIfBadProxy(a)
            }

            @Suppress("DEPRECATION") // API 26–28: runs after onCreate (best-effort)
            override fun onActivityCreated(a: Activity, s: Bundle?) {
                if (Build.VERSION.SDK_INT < 29) killIfBadProxy(a)
            }

            private fun killIfBadProxy(a: Activity) {
                if (a.javaClass.name == "com.android.billingclient.api.ProxyBillingActivity") {
                    val extrasOk = a.intent?.extras?.isEmpty == false
                    val launchedByUs = BillingGuard.isExpecting()
                    // ✅ SAFEST rule: Only kill if extras are missing.
                    //    (We just log when extras exist but guard is false.)
                    if (!extrasOk) {
                        try {
                            tracker.trackEvent(
                                "billing_proxy_killed", mapOf(
                                    "extrasOk" to extrasOk,
                                    "launchedByUs" to launchedByUs
                                )
                            )
                        } catch (_: Throwable) {
                            // do noting
                        }
                        try {
                            a.finish()
                        } catch (_: Throwable) {
                            // do noting
                        }
                    } else {
                        try {
                            tracker.trackEvent(
                                "billing_proxy_seen", mapOf(
                                    "extrasOk" to true,
                                    "launchedByUs" to launchedByUs
                                )
                            )
                        } catch (_: Throwable) {
                            // do noting
                        }
                    }
                }
            }

            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })

        billingManager = BillingEntitlementManager(this, tracker)
        CoroutineScope(SupervisorJob() + DispatchersProvider.io).launch {
            runCatching { billingManager.start() }.onFailure { tracker.trackError(it) }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        billingManager.end()
    }
}
