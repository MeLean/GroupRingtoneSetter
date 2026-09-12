package com.milen.grounpringtonesetter.billing

import BillingGuard
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)

        try {
            context.startActivity(
                validBuyIntent()
                    .setClassName(context, PROXY_BILLING_ACTIVITY_CLASS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            assertTrue(activityCreated.await(5, TimeUnit.SECONDS))
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
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
