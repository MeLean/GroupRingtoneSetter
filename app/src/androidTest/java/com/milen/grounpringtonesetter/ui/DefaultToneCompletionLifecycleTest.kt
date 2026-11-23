package com.milen.grounpringtonesetter.ui

import android.os.SystemClock
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.milen.grounpringtonesetter.MainActivity
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.ui.ads.InterstitialAdShowResult
import com.milen.grounpringtonesetter.ui.defaulttones.DefaultToneCompletionDialog
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneType
import com.milen.grounpringtonesetter.ui.defaulttones.viewmodel.DeviceDefaultTonesViewModel
import com.milen.grounpringtonesetter.ui.defaulttones.viewmodel.DeviceDefaultTonesViewModelFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultToneCompletionLifecycleTest {
    @get:Rule val permissions = contactsPermissionRule
    private val app get() = regressionApp()

    @Before
    fun setUp() {
        app.resetUiScenario()
        app.fakeInterstitial.deferCompletion = true
    }

    @Test
    fun completionSurvivesActivityRecreationAndDialogRestorationWithoutRepeatingAdOrFeedback() {
        launchMain().use { scenario ->
            scenario.navigate(R.id.action_home_to_deviceDefaultTones)
            scenario.applyToneAndAwaitAd()
            scenario.recreate()
            scenario.onActivity {
                val callback = app.fakeInterstitial.pendingCallbacks.single()
                callback(InterstitialAdShowResult.SHOWN)
                callback(InterstitialAdShowResult.SHOWN) // Duplicate SDK terminal callback.
            }
            scenario.awaitDialog()
            scenario.recreate() // Restore an already visible dialog, not a second copy.
            scenario.awaitDialog()
            scenario.onActivity {
                assertEquals(1, app.fakeInterstitial.showCalls)
                assertEquals(true, model(it).state.value.pendingAdCompletion?.isReady)
            }
            onView(withText(R.string.ok)).perform(click())
            scenario.recreate()
            onView(withText(R.string.everything_set)).check(doesNotExist())
            scenario.onActivity {
                assertTrue(currentScreen(it).childFragmentManager.fragments.none { dialog ->
                    dialog is DefaultToneCompletionDialog
                })
                assertEquals(1, app.fakeDefaultTones.appliedTones.size)
            }
        }
    }

    @Test
    fun completionWhileAnotherDestinationIsVisibleWaitsForReturnToDefaultTones() {
        launchMain().use { scenario ->
            scenario.navigate(R.id.action_home_to_deviceDefaultTones)
            scenario.applyToneAndAwaitAd()
            scenario.onActivity {
                navHost(it).navController.navigate(R.id.backupRestoreFragment)
                it.supportFragmentManager.executePendingTransactions()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { app.fakeInterstitial.pendingCallbacks.single()(InterstitialAdShowResult.SKIPPED) }
            onView(withText(R.string.everything_set)).check(doesNotExist())
            scenario.onActivity { navHost(it).navController.popBackStack() }
            scenario.awaitDialog()
            scenario.onActivity { assertEquals(1, app.fakeInterstitial.showCalls) }
            onView(withText(R.string.ok)).perform(click())
        }
    }

    private fun ActivityScenario<MainActivity>.applyToneAndAwaitAd() {
        onActivity { activity ->
            app.fakeBilling.state.value = EntitlementState.NOT_OWNED
            model(activity).onTonePicked(DeviceDefaultToneType.NOTIFICATION, null)
        }
        awaitCondition { app.fakeInterstitial.pendingCallbacks.size == 1 }
    }

    private fun ActivityScenario<MainActivity>.awaitDialog() {
        awaitCondition { activity ->
            currentScreen(activity).childFragmentManager.fragments
                .filterIsInstance<DefaultToneCompletionDialog>().count { it.dialog?.isShowing == true } == 1
        }
        onView(withText(R.string.everything_set)).check(matches(isDisplayed()))
    }

    private fun ActivityScenario<MainActivity>.awaitCondition(condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var satisfied = false
            onActivity { satisfied = condition(it) }
            if (satisfied) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Timed out waiting for lifecycle completion")
    }

    private fun model(activity: MainActivity): DeviceDefaultTonesViewModel =
        ViewModelProvider(currentScreen(activity), DeviceDefaultTonesViewModelFactory.provideFactory(activity))[
            DeviceDefaultTonesViewModel::class.java
        ]

    private fun navHost(activity: MainActivity): NavHostFragment =
        activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment

    private fun currentScreen(activity: MainActivity): Fragment =
        requireNotNull(navHost(activity).childFragmentManager.primaryNavigationFragment)
}
