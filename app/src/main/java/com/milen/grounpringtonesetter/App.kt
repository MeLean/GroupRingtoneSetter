package com.milen.grounpringtonesetter

import BillingGuard
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.milen.grounpringtonesetter.billing.BillingEntitlementManager
import com.milen.grounpringtonesetter.billing.NoopBillingResultActivity
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
                    .penaltyLog().build()
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog().build()
            )
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(a: Activity, s: Bundle?) {
                // API 29+ runs here before Activity.onCreate()
                patchOrKillBadProxy(a)
            }
            @Suppress("DEPRECATION")
            override fun onActivityCreated(a: Activity, s: Bundle?) {
                // Fallback for <29 (may be too late to patch, but keeps parity)
                if (Build.VERSION.SDK_INT < 29) patchOrKillBadProxy(a)
            }

            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })

        billingManager = BillingEntitlementManager(this, tracker)
        tracker.trackEvent("billing_manager_created", mapOf("app_onCreate_complete" to true))
        val billingScope = CoroutineScope(SupervisorJob() + DispatchersProvider.io)
        billingScope.launch {
            tracker.trackEvent("billing_start_launched", mapOf("coroutine_started" to true))
            val startResult = runCatching { 
                billingManager.start() 
            }
            startResult.onSuccess {
                tracker.trackEvent("billing_start_completed_success", mapOf("result" to "success"))
            }.onFailure { e ->
                tracker.trackEvent(
                    "billing_start_completed_failure",
                    mapOf(
                        "error_type" to e::class.java.simpleName,
                        "error_message" to (e.message ?: "unknown"),
                        "is_cancellation" to (e is kotlinx.coroutines.CancellationException)
                    )
                )
                tracker.trackError(e)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        billingManager.end()
    }

    private fun patchOrKillBadProxy(a: Activity) {
        if (a.javaClass.name != "com.android.billingclient.api.ProxyBillingActivity") return

        tracker.trackEvent(
            "billing_proxy_guard_entered",
            mapOf("activity_class" to a::javaClass.name)
        )


        val launchedByUs = BillingGuard.isExpecting()
        val hasValidExtras = BillingGuard.hasValidBillingExtras(a.intent)

        // If a legit launch → allow immediately
        if (launchedByUs && hasValidExtras) {
            try {
                tracker.trackEvent(
                    "billing_proxy_allowed",
                    mapOf("validExtras" to true, "launchedByUs" to true)
                )
            } catch (_: Throwable) {
            }
            return
        }

        // Rogue launch (pre-launch bot, deep link fuzzing, etc.)
        // On API 29+ we are still before onCreate(), so we can PATCH missing extras.
        var patched = false
        try {
            val extras = a.intent?.extras
            if (Build.VERSION.SDK_INT >= 29) {
                if (extras == null || extras.isEmpty || !hasValidExtras) {
                    val flags = PendingIntent.FLAG_IMMUTABLE
                    val noopIntent = Intent(a, NoopBillingResultActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)

                    val pi = PendingIntent.getActivity(a, 0, noopIntent, flags)

                    val i = a.intent ?: Intent().also { a.intent = it }
                    i.putExtra("BUY_INTENT", pi) // the one ProxyBillingActivity dereferences
                    // add a benign receiver key some older builds check for:
                    i.putExtra("result_receiver", intArrayOf()) // harmless placeholder

                    patched = true
                    tracker.trackEvent(
                        "billing_proxy_patched",
                        mapOf("launchedByUs" to launchedByUs)
                    )
                }
            }
        } catch (t: Throwable) {
            try {
                tracker.trackError(t)
            } catch (_: Throwable) {
            }
        }

        if (!patched) {
            // Last resort: close it (works on many devices, but the patch above is the real fix)
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
            runCatching { a.finish() }
        }
    }
}
