package com.milen.grounpringtonesetter.ui

import android.app.Activity
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.junit.runners.Parameterized
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.ui.ads.InterstitialAdShowResult
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference

/** Real external Android picker + Activity Result routing. Repository/ad gateways remain deterministic. */
@RunWith(Parameterized::class)
class P0RingtoneLifecycleUiTest(private val destroyActivities: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "keepActivitiesDestroyed={0}")
        fun activityModes(): List<Array<Boolean>> = listOf(arrayOf(false), arrayOf(true))
    }
    private lateinit var environment: P0UiEnvironment
    private lateinit var tones: List<P0UiEnvironment.Tone>
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val app get() = regressionApp()

    @Test
    fun ringtoneNotificationAndAlarmReturnFromRealPickerWithKeepActivitiesOffAndOn() = withActivityModes { destroy ->
        app.resetUiScenario()
        launchMain().use { scenario ->
            scenario.navigate(R.id.action_home_to_deviceDefaultTones)
            scenario.onActivity { app.fakeBilling.state.value = EntitlementState.NOT_OWNED }
            DeviceDefaultToneType.entries.forEachIndexed { index, type ->
                val host = arrayOfNulls<WeakReference<Activity>>(1)
                scenario.onActivity { host[0] = WeakReference(it) }
                val button = when (type) {
                    DeviceDefaultToneType.RINGTONE -> R.id.crbChangeDefaultRingtone
                    DeviceDefaultToneType.NOTIFICATION -> R.id.crbChangeDefaultNotification
                    DeviceDefaultToneType.ALARM -> R.id.crbChangeDefaultAlarm
                }
                clickRoundedButton(button)
                onView(withText(R.string.ringtone_source_system)).perform(click())
                environment.selectSystemTone(tones[index % 2])
                awaitApp()
                onView(withText(R.string.everything_set)).check(matches(isDisplayed()))
                assertEquals(index + 1, app.fakeDefaultTones.appliedTones.size)
                assertEquals(type, app.fakeDefaultTones.appliedTones.last().first)
                assertEquals(tones[index % 2].uri, app.fakeDefaultTones.appliedTones.last().second)
                if (destroy) assertTrue(host[0]?.get()?.isDestroyed != false)
                onView(withText(R.string.ok)).perform(click())
                onView(withText(R.string.everything_set)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            }
            assertEquals(3, app.fakeInterstitial.showCalls)
        }
    }

    @Test
    fun sixImmediateGroupAssignmentsWithAvailableAndUnavailableGatewaySurviveExternalPickerRecreation() = withActivityModes { destroy ->
        val person = contact(1, "Test contact")
        app.resetUiScenario(contacts = listOf(person), groups = listOf(group("p0", "Test group", listOf(person))))
        launchMain().use { scenario ->
            scenario.onActivity { app.fakeBilling.state.value = EntitlementState.NOT_OWNED }
            repeat(6) { index ->
                val host = arrayOfNulls<WeakReference<Activity>>(1)
                scenario.onActivity {
                    host[0] = WeakReference(it)
                    it.findViewById<RecyclerView>(R.id.rwGroupItems).scrollToPosition(0)
                    app.fakeInterstitial.result = if (index % 2 == 0) InterstitialAdShowResult.SHOWN else InterstitialAdShowResult.SKIPPED
                }
                clickRoundedButton(R.id.crb_choose_ringtone)
                onView(withText(R.string.ringtone_source_system)).perform(click())
                environment.selectSystemTone(tones[index % 2])
                awaitApp()
                onView(withText(R.string.everything_set)).check(matches(isDisplayed()))
                assertEquals(index + 1, app.fakeContacts.ringtoneUpdates.size)
                assertEquals(tones[index % 2].uri.toString(), app.fakeContacts.ringtoneUpdates.last().second)
                assertEquals(index + 1, app.fakeInterstitial.showCalls)
                if (destroy) assertTrue(host[0]?.get()?.isDestroyed != false)
                onView(withText(R.string.ok)).perform(click())
                onView(withText(R.string.everything_set)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            }
        }
    }

    @Test
    fun cancellingSystemAndFilePickerAfterActivityDestructionDoesNotAssignOrAdvertise() = withActivityModes {
        app.resetUiScenario()
        launchMain().use { scenario ->
            scenario.navigate(R.id.action_home_to_deviceDefaultTones)
            scenario.onActivity { app.fakeBilling.state.value = EntitlementState.NOT_OWNED }
            listOf(R.string.ringtone_source_system, R.string.ringtone_source_file).forEach { source ->
                clickRoundedButton(R.id.crbChangeDefaultAlarm)
                onView(withText(source)).perform(click())
                environment.await("actual external picker before cancellation") {
                    if (source == R.string.ringtone_source_file) device.currentPackageName?.contains("documentsui") == true
                    else device.currentPackageName != app.packageName && device.hasObject(By.res("android", "button1"))
                }
                device.pressBack()
                awaitApp()
                onView(withText(R.string.device_default_tones_title)).check(matches(isDisplayed()))
                assertTrue(app.fakeDefaultTones.appliedTones.isEmpty())
                assertEquals(0, app.fakeInterstitial.showCalls)
            }
        }
    }

    @Test
    fun realFilePickerSelectionReturnsToCorrectToneTargetWithKeepActivitiesOffAndOn() = withActivityModes { destroy ->
        app.resetUiScenario()
        launchMain().use { scenario ->
            scenario.navigate(R.id.action_home_to_deviceDefaultTones)
            scenario.onActivity { app.fakeBilling.state.value = EntitlementState.NOT_OWNED }
            tones.forEachIndexed { index, tone ->
                val host = arrayOfNulls<WeakReference<Activity>>(1)
                scenario.onActivity { host[0] = WeakReference(it) }
                clickRoundedButton(R.id.crbChangeDefaultAlarm)
                onView(withText(R.string.ringtone_source_file)).perform(click())
                environment.selectFileTone(tone)
                awaitApp()
                onView(withText(R.string.everything_set)).check(matches(isDisplayed()))
                assertEquals(index + 1, app.fakeDefaultTones.appliedTones.size)
                assertEquals(DeviceDefaultToneType.ALARM, app.fakeDefaultTones.appliedTones.last().first)
                currentToneMatches(tone, app.fakeDefaultTones.appliedTones.last().second)
                assertEquals(index + 1, app.fakeInterstitial.showCalls)
                if (destroy) assertTrue(host[0]?.get()?.isDestroyed != false)
                onView(withText(R.string.ok)).perform(click())
                onView(withText(R.string.everything_set)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            }
            assertNotEquals(tones[0].uri, tones[1].uri)
        }
    }

    private fun currentToneMatches(tone: P0UiEnvironment.Tone, selected: android.net.Uri?) {
        val resolver = app.contentResolver
        requireNotNull(resolver.openInputStream(requireNotNull(selected))).use { actual ->
            requireNotNull(resolver.openInputStream(tone.uri)).use { expected ->
                assertArrayEquals("Picker must return the known fixture ${tone.title} (${tone.uri})", expected.readBytes(), actual.readBytes())
            }
        }
    }

    private fun awaitApp() = await { device.currentPackageName == app.packageName }
    private fun await(condition: () -> Boolean) = environment.await("external picker return", condition)

    private fun withActivityModes(block: (Boolean) -> Unit) {
        P0UiEnvironment().use { current ->
            environment = current
            current.prepare()
            tones = listOf(current.createTone(), current.createTone(second = true))
            current.mode(destroyActivities)
            block(destroyActivities)
        }
    }
}
