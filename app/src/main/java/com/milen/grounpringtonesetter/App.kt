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

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(a: Activity, s: Bundle?) {
                killIfBadProxy(a)
            }

            @Suppress("DEPRECATION")
            override fun onActivityCreated(a: Activity, s: Bundle?) {
                if (Build.VERSION.SDK_INT < 29) killIfBadProxy(a)
            }

            private fun killIfBadProxy(a: Activity) {
                if (a.javaClass.name != "com.android.billingclient.api.ProxyBillingActivity") return

                val hasValidExtras = BillingGuard.hasValidBillingExtras(a.intent)
                val launchedByUs = BillingGuard.isExpecting()

                if (!hasValidExtras || !launchedByUs) {
                    try {
                        tracker.trackEvent(
                            "billing_proxy_killed", mapOf(
                                "validExtras" to hasValidExtras,
                                "launchedByUs" to launchedByUs,
                                "hasIntent" to (a.intent != null),
                                "hasExtras" to (a.intent?.extras != null),
                                "extrasEmpty" to (a.intent?.extras?.isEmpty ?: true)
                            )
                        )
                    } catch (_: Throwable) {
                    }
                    try {
                        a.finish()
                    } catch (_: Throwable) {
                    }
                } else {
                    try {
                        tracker.trackEvent(
                            "billing_proxy_allowed", mapOf(
                                "validExtras" to true,
                                "launchedByUs" to true
                            )
                        )
                    } catch (_: Throwable) {
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