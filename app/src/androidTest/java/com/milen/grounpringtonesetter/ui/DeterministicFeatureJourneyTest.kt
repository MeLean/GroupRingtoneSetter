package com.milen.grounpringtonesetter.ui

import android.Manifest
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.clearText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.pressImeActionButton
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.milen.grounpringtonesetter.MainActivity
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.testing.RegressionTestApplication
import com.milen.grounpringtonesetter.ui.picker.PickerScreenFragment
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeterministicFeatureJourneyTest {

    @get:Rule
    val contactsPermissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
    )

    private lateinit var app: RegressionTestApplication

    @Before
    fun seedScenario() {
        app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as RegressionTestApplication
        
        // Reset fake repository state
        app.fakeContacts.createdNames.clear()
        app.fakeContacts.renamedGroups.clear()
        app.fakeContacts.deletedGroups.clear()
        
        val contact = Contact(
            id = 1L,
            lookupKey = "lookup-1",
            name = "Alice",
            phone = "+359100000001",
            ringtoneUriStr = null,
        )
        app.fakeContacts.allContacts.value = listOf(contact)
        app.fakeContacts.labelsFlow.value = listOf(
            LabelItem(
                id = "friends",
                groupName = "Friends",
                contacts = listOf(contact),
            )
        )
    }

    @Test
    fun createSearchAndRenameGroupJourneyPersistsThroughFeatureRepository() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(groupName("Friends")).check(matches(isDisplayed()))

            clickChildButton(R.id.btnAddGroup)
            onView(withId(R.id.editTextInput))
                .perform(replaceText("Travel"), closeSoftKeyboard())
            clickChildButton(R.id.crbDone)

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(listOf("Travel"), app.fakeContacts.createdNames)
            onView(groupName("Travel")).check(matches(isDisplayed()))

            clickChildImageButton(R.id.ctcibToggleSearch)
            onView(allOf(withId(R.id.editTextInput), isDescendantOfA(withId(R.id.civGroupSearch))))
                .perform(typeText("Travel"), pressImeActionButton(), closeSoftKeyboard())
            onView(groupName("Travel")).check(matches(isDisplayed()))
            onView(groupName("Friends")).check(doesNotExist())

            clickChildImageButton(R.id.ctcibToggleSearch)
            scenario.onActivity { activity ->
                val createdGroup = app.fakeContacts.labelsFlow.value
                    .first { label -> label.groupName == "Travel" }
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                navHost.navController.navigate(
                    R.id.action_home_to_picker,
                    PickerScreenFragment.argsForRename(createdGroup),
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onView(withId(R.id.editTextInput))
                .perform(clearText(), typeText("Trips"), closeSoftKeyboard())
            clickChildButton(R.id.crbDone)

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals("Trips", app.fakeContacts.renamedGroups.last().second)
            onView(groupName("Trips")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun defaultTonesAndBackupScreensRenderFromDeterministicExternalGateways() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                navHost.navController.navigate(R.id.action_home_to_deviceDefaultTones)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            onView(withText(R.string.device_default_tones_title)).check(matches(isDisplayed()))
            onView(withText("Test ringtone")).check(matches(isDisplayed()))
            onView(withText("Test notification")).check(matches(isDisplayed()))
            onView(withText("Test alarm")).check(matches(isDisplayed()))

            clickChildButton(R.id.crbDoneDefaultTones)
            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                navHost.navController.navigate(R.id.action_home_to_backupRestore)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            onView(withText(R.string.backup_restore_description)).check(matches(isDisplayed()))
            onView(withText(R.string.backup_restore_export_action)).check(matches(isDisplayed()))
            onView(withText(R.string.backup_restore_restore_action)).check(matches(isDisplayed()))
        }
    }

    private fun clickChildButton(parentId: Int) {
        onView(allOf(withId(R.id.btnMain), isDescendantOfA(withId(parentId)))).perform(click())
    }

    private fun clickChildImageButton(parentId: Int) {
        onView(allOf(withId(R.id.imageButton), isDescendantOfA(withId(parentId)))).perform(click())
    }

    private fun groupName(name: String) = allOf(
        withId(R.id.ctvGroupName),
        withText(name),
    )
}
