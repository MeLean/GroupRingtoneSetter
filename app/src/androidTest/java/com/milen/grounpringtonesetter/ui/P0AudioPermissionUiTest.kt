package com.milen.grounpringtonesetter.ui

import android.content.pm.PackageManager
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.milen.grounpringtonesetter.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Denied cases are launched separately by run-p0-ui.py: revoking our own grant kills instrumentation. */
@RunWith(AndroidJUnit4::class)
class P0AudioPermissionUiTest {
    @Test fun grantedAudioOpensSourceWithoutPermissionDialog() = P0UiEnvironment().use { env ->
        env.prepare()
        env.mode(false)
        seedGroup()
        launchMain().use {
            clickRoundedButton(R.id.crb_choose_ringtone)
            onView(withText(R.string.select_ringtone_source)).check(matches(isDisplayed()))
            assertFalse(env.device.hasObject(By.res("com.android.permissioncontroller", "permission_allow_button")))
        }
    }

    @Test fun deniedAudioDoesNotAssignOrAdvertise() = exerciseDeniedAudio(requestAgain = false)

    @Test fun deniedAudioCanBeRequestedAgainAndGranted() = exerciseDeniedAudio(requestAgain = true)

    private fun exerciseDeniedAudio(requestAgain: Boolean) = P0UiEnvironment().use { env ->
        env.prepare(grantAudio = false)
        env.mode(false)
        val app = regressionApp()
        P0UiEnvironment.audioPermissions().forEach {
            assertEquals("SETUP: use run-p0-ui.py to start this case with audio permission denied", PackageManager.PERMISSION_DENIED,
                app.checkSelfPermission(it))
        }
        seedGroup()
        launchMain().use {
            clickRoundedButton(R.id.crb_choose_ringtone)
            permissionButton(env, "permission_deny_button").click()
            onView(withText(R.string.audio_permission_rationale)).check(matches(isDisplayed()))
            assertTrue(app.fakeContacts.ringtoneUpdates.isEmpty())
            assertEquals(0, app.fakeInterstitial.showCalls)
            if (requestAgain) {
                onView(withText(R.string.confirm)).perform(click())
                permissionButton(env, "permission_allow_button").click()
                onView(withText(R.string.select_ringtone_source)).check(matches(isDisplayed()))
                P0UiEnvironment.audioPermissions().forEach { permission ->
                    assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(permission))
                }
            } else {
                onView(withText(R.string.cancel)).perform(click())
                P0UiEnvironment.audioPermissions().forEach { permission ->
                    assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(permission))
                }
            }
            assertTrue(app.fakeContacts.ringtoneUpdates.isEmpty())
            assertEquals(0, app.fakeInterstitial.showCalls)
        }
    }

    private fun permissionButton(env: P0UiEnvironment, id: String): androidx.test.uiautomator.UiObject2 {
        // AOSP and Google images use the same resource names with different package names.
        val selector = By.res(java.util.regex.Pattern.compile(".*:id/$id"))
        return requireNotNull(env.device.wait(Until.findObject(selector), 10_000)) {
            "SETUP: expected Android runtime permission control $id in ${env.device.currentPackageName}"
        }
    }

    private fun seedGroup() {
        val person = contact(1, "P0 permission contact")
        regressionApp().resetUiScenario(listOf(person), listOf(group("p0-permission", "P0 permission group", listOf(person))))
        regressionApp().fakeBilling.state.value = com.milen.grounpringtonesetter.billing.EntitlementState.NOT_OWNED
    }
}
