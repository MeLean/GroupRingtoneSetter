package com.milen.grounpringtonesetter.billing

import BillingGuard
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.os.Parcelable
import android.os.ResultReceiver
import com.android.billingclient.api.BillingClient
import com.milen.grounpringtonesetter.utils.Telemetry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BillingAppComponentFactoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val factory = BillingAppComponentFactory()
    private val classLoader = requireNotNull(javaClass.classLoader)

    @After
    fun tearDown() {
        BillingPlatformCapabilities.detector = ReflectiveBillingPlatformCapabilityDetector
        BillingGuard.endLaunch()
    }

    @Test
    @Suppress("DEPRECATION")
    fun manifestRegistersBillingFactory() {
        val applicationInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            0,
        )

        assertEquals(BillingAppComponentFactory::class.java.name, applicationInfo.appComponentFactory)
    }

    @Test
    fun incompatibleDirectProxyCreationUsesFallbackRegardlessOfLaunchFlag() {
        BillingPlatformCapabilities.detector = BillingPlatformCapabilityDetector { false }
        val intent = validBuyIntent()

        BillingGuard.endLaunch()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(createProxy(intent) is BillingUnavailableActivity)
        }

        BillingGuard.beginLaunch()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(createProxy(intent) is BillingUnavailableActivity)
        }
    }

    @Test
    fun registeredFrameworkFactoryInterceptsProxyBeforeGoogleOnCreate() {
        BillingPlatformCapabilities.detector = BillingPlatformCapabilityDetector { false }
        val activityCreated = CountDownLatch(1)
        val activityDestroyed = CountDownLatch(1)
        BillingGuard.beginLaunch()
        val application = context.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) {
                if (activity is BillingUnavailableActivity) activityCreated.countDown()
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (activity is BillingUnavailableActivity && activity.isFinishing) {
                    activityDestroyed.countDown()
                }
            }
        }
        application.registerActivityLifecycleCallbacks(callbacks)

        try {
            context.startActivity(
                validBuyIntent()
                    .setClassName(context, PROXY_BILLING_ACTIVITY_CLASS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            assertTrue(activityCreated.await(5, TimeUnit.SECONDS))
            assertTrue("Fallback must finish through its own lifecycle", activityDestroyed.await(5, TimeUnit.SECONDS))
            assertTrue("A blocked direct launch must not clear another launch", BillingGuard.isExpecting())
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    @Test
    fun messageReceiverMayBeAbsentNullOrValidButNotWronglyTyped() {
        BillingPlatformCapabilities.detector = BillingPlatformCapabilityDetector { true }
        val messageIntent = Intent().putExtra(
            "IN_APP_MESSAGE_INTENT",
            validBuyIntent().getParcelableExtra<PendingIntent>("BUY_INTENT"),
        )
        val allowed = listOf(
            messageIntent,
            Intent(messageIntent).putExtra("in_app_message_result_receiver", null as Parcelable?),
            Intent(messageIntent).putExtra("in_app_message_result_receiver", ResultReceiver(null)),
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            allowed.forEach { intent ->
                assertEquals(PROXY_BILLING_ACTIVITY_CLASS, createProxy(intent).javaClass.name)
            }
            val wrongReceiver = Intent(messageIntent)
                .putExtra("in_app_message_result_receiver", intArrayOf())
            assertTrue(createProxy(wrongReceiver) is BillingUnavailableActivity)
        }
    }

    @Test
    fun serializedLaunchIntentRemainsValidWithFreshFactoryAndClearedLaunchFlag() {
        // Exercises persisted Intent data, not a claim of actual OS process-death recovery.
        val parcel = Parcel.obtain()
        val restored = try {
            validBuyIntent().writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
        BillingPlatformCapabilities.detector = BillingPlatformCapabilityDetector { true }
        BillingGuard.endLaunch()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = BillingAppComponentFactory().instantiateActivity(
                classLoader, PROXY_BILLING_ACTIVITY_CLASS, restored,
            )
            assertEquals(PROXY_BILLING_ACTIVITY_CLASS, activity.javaClass.name)
        }
    }

    @Test
    fun supportedValidProxyCreationDelegatesWhenLaunchFlagWasLost() {
        BillingPlatformCapabilities.detector = BillingPlatformCapabilityDetector { true }
        BillingGuard.endLaunch()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = createProxy(validBuyIntent())

            assertEquals(PROXY_BILLING_ACTIVITY_CLASS, activity.javaClass.name)
            assertFalse(activity is BillingUnavailableActivity)
        }
    }

    @Test
    fun malformedProxyExtrasUseFallbackWithoutSyntheticPayload() {
        BillingPlatformCapabilities.detector = BillingPlatformCapabilityDetector { true }
        val missing = Intent()
        val wrongType = Intent().putExtra("BUY_INTENT", intArrayOf())

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(createProxy(missing) is BillingUnavailableActivity)
            assertTrue(createProxy(wrongType) is BillingUnavailableActivity)
        }
        assertFalse(missing.hasExtra("BUY_INTENT"))
        assertTrue(wrongType.getIntArrayExtra("BUY_INTENT")?.isEmpty() == true)
    }

    @Test
    fun unreadableExtrasUseFallback() {
        BillingPlatformCapabilities.detector = BillingPlatformCapabilityDetector { true }
        val unreadable = object : Intent() {
            override fun hasExtra(name: String?): Boolean = error("Unreadable parcel")
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(createProxy(unreadable) is BillingUnavailableActivity)
        }
    }

    @Test
    fun unrelatedActivityStillUsesAndroidXFactoryPath() {
        BillingPlatformCapabilities.detector = BillingPlatformCapabilityDetector { false }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = factory.instantiateActivityCompat(
                classLoader,
                Activity::class.java.name,
                Intent(),
            )

            assertEquals(Activity::class.java, activity.javaClass)
        }
    }

    private fun createProxy(intent: Intent): Activity = factory.instantiateActivityCompat(
        classLoader,
        PROXY_BILLING_ACTIVITY_CLASS,
        intent,
    )

    @Test
    fun incompatibleManagerCompletesRepeatedAttemptsWithoutListenerAndPreservesGrace() = runBlocking {
        val grace = AdFreeGraceStore(context)
        val previousGrace = grace.readAdFreeUntil()
        val until = System.currentTimeMillis() + AdFreeGraceStore.GRACE_TTL_MILLIS
        val telemetry = object : Telemetry {
            override fun trackEvent(eventName: String, params: Map<String, Any>?) = Unit
            override fun trackError(error: Throwable) = Unit
        }
        val manager = BillingEntitlementManager(
            context.applicationContext as Application,
            telemetry,
            BillingPlatformCapabilityDetector { false },
        )
        val activity = java.util.concurrent.atomic.AtomicReference<Activity>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.set(Activity()) }
        try {
            grace.saveAdFreeUntil(until)
            val previousState = manager.state.value
            repeat(2) {
                val result = withTimeout(2_000) { manager.launchPurchase(activity.get()) }
                assertEquals(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE, result)
                assertEquals(previousState, manager.state.value)
                assertEquals(until, grace.readAdFreeUntil())
            }
        } finally {
            manager.end()
            if (previousGrace == null) grace.clear() else grace.saveAdFreeUntil(previousGrace)
        }
    }

    private fun validBuyIntent(): Intent {
        val target = Intent(context, Activity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            42,
            target,
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Intent().putExtra("BUY_INTENT", pendingIntent)
    }
}
