package com.milen.grounpringtonesetter.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneType
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultTonesAndBackupUiTest {

    @get:Rule
    val permissions = contactsPermissionRule

    private val app by lazy(::regressionApp)

    @Before
    fun setUp() {
        app.resetUiScenario()
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    @Test
    fun defaultRingtoneSystemPickerAppliesSelectionAndShowsSuccess() {
        val selectedUri = Uri.parse("content://media/internal/audio/media/100")
        stubTonePickerResult(selectedUri)

        launchMain().use { scenario ->
            scenario.navigate(R.id.action_home_to_deviceDefaultTones)
            clickRoundedButton(R.id.crbChangeDefaultRingtone)
            onView(allOf(withText(R.string.ringtone_source_system), isDisplayed())).perform(click())

            intended(hasAction(RingtoneManager.ACTION_RINGTONE_PICKER))
            onView(withText(R.string.everything_set)).check(matches(isDisplayed()))
            assertEquals(
                DeviceDefaultToneType.RINGTONE to selectedUri,
                app.fakeDefaultTones.appliedTones.single()
            )
        }
    }

    @Test
    fun silentNotificationSelectionIsAccepted() {
        stubTonePickerResult(null)

        launchMain().use { scenario ->
            scenario.navigate(R.id.action_home_to_deviceDefaultTones)
            clickRoundedButton(R.id.crbChangeDefaultNotification)
            onView(allOf(withText(R.string.ringtone_source_system), isDisplayed())).perform(click())

            onView(withText(R.string.everything_set)).check(matches(isDisplayed()))
            assertEquals(
                DeviceDefaultToneType.NOTIFICATION to null,
                app.fakeDefaultTones.appliedTones.single()
            )
        }
    }

    @Test
    fun customAlarmFileIsImportedAndApplied() {
        val selectedUri = Uri.parse("content://test/audio/custom-alarm.mp3")
        intending(hasAction(Intent.ACTION_GET_CONTENT)).respondWith(
            android.app.Instrumentation.ActivityResult(
                Activity.RESULT_OK,
                Intent().setData(selectedUri)
            )
        )

        launchMain().use { scenario ->
            scenario.navigate(R.id.action_home_to_deviceDefaultTones)
            clickRoundedButton(R.id.crbChangeDefaultAlarm)
            onView(allOf(withText(R.string.ringtone_source_file), isDisplayed())).perform(click())

            intended(hasAction(Intent.ACTION_GET_CONTENT))
            onView(withText(R.string.everything_set)).check(matches(isDisplayed()))
            assertEquals(
                DeviceDefaultToneType.ALARM to selectedUri,
                app.fakeDefaultTones.appliedTones.single()
            )
        }
    }

    @Test
    fun missingWriteSettingsPermissionCanBeCancelledWithoutApplyingTone() {
        app.fakeDefaultTones.canWrite = false
        stubTonePickerResult(Uri.parse("content://media/internal/audio/media/200"))

        launchMain().use { scenario ->
            scenario.navigate(R.id.action_home_to_deviceDefaultTones)
            clickRoundedButton(R.id.crbChangeDefaultRingtone)
            onView(allOf(withText(R.string.ringtone_source_system), isDisplayed())).perform(click())

            onView(withText(R.string.default_tone_write_settings_rationale))
                .check(matches(isDisplayed()))
            onView(allOf(withText(R.string.cancel), isDisplayed())).perform(click())
            assertTrue(app.fakeDefaultTones.appliedTones.isEmpty())
        }
    }

    @Test
    fun doneReturnsFromDefaultTonesToHome() {
        launchMain().use { scenario ->
            scenario.navigate(R.id.action_home_to_deviceDefaultTones)
            clickRoundedButton(R.id.crbDoneDefaultTones)

            onView(withId(R.id.no_item_disclaimer)).check(matches(isDisplayed()))
            onView(withText(R.string.device_default_tones_title)).check(doesNotExist())
        }
    }

    @Test
    fun backupIsUnlockedForOwnedUsersAndLockedForOtherEntitlementStates() {
        app.fakeBilling.state.value = EntitlementState.OWNED
        launchMain().use { scenario ->
            scenario.navigate(R.id.action_home_to_backupRestore)
            onView(withId(R.id.llBackupRestoreUnlocked)).check(matches(isDisplayed()))
            onView(withId(R.id.llBackupRestoreLocked)).check(matches(notDisplayed()))
        }

        app.fakeBilling.state.value = EntitlementState.PENDING
        launchMain().use { scenario ->
            scenario.navigate(R.id.action_home_to_backupRestore)
            onView(withId(R.id.llBackupRestoreLocked)).check(matches(isDisplayed()))
            onView(withText(R.string.backup_restore_locked_message)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun backupPurchaseActionLaunchesBillingOnlyOnceForAQuickDoubleTap() {
        app.fakeBilling.state.value = EntitlementState.NOT_OWNED

        launchMain().use { scenario ->
            scenario.navigate(R.id.action_home_to_backupRestore)
            clickRoundedButton(R.id.crbBackupRestoreRemoveAds)
            clickRoundedButton(R.id.crbBackupRestoreRemoveAds)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals(1, app.fakeBilling.purchaseLaunchCount)
        }
    }

    @Test
    fun toolbarInfoDescribesCurrentDefaultToneAndBackupScreens() {
        launchMain().use { scenario ->
            scenario.navigate(R.id.action_home_to_deviceDefaultTones)
            onView(withId(R.id.btnInfoAction)).perform(click())
            onView(withText(R.string.device_default_tones_info_text)).check(matches(isDisplayed()))
            onView(allOf(withText(R.string.ok), isDisplayed())).perform(click())

            clickRoundedButton(R.id.crbDoneDefaultTones)
            scenario.navigate(R.id.action_home_to_backupRestore)
            onView(withId(R.id.btnInfoAction)).perform(click())
            onView(withText(R.string.backup_restore_info_text)).check(matches(isDisplayed()))
        }
    }

    private fun stubTonePickerResult(uri: Uri?) {
        val data = Intent()
        if (uri != null) {
            data.putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, uri)
        }
        intending(hasAction(RingtoneManager.ACTION_RINGTONE_PICKER)).respondWith(
            android.app.Instrumentation.ActivityResult(Activity.RESULT_OK, data)
        )
    }

    private fun notDisplayed() = org.hamcrest.Matchers.not(isDisplayed())
}
