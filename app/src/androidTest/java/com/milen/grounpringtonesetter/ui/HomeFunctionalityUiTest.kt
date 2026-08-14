package com.milen.grounpringtonesetter.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.pressImeActionButton
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.hasSibling
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.milen.grounpringtonesetter.MainActivity
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.accounts.AccountId
import com.milen.grounpringtonesetter.data.sources.ContactSource
import com.milen.grounpringtonesetter.ui.home.HomeDisplayPreferences
import com.milen.grounpringtonesetter.ui.home.HomeScreen
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeFunctionalityUiTest {

    @get:Rule
    val permissions = contactsPermissionRule

    private val app by lazy(::regressionApp)
    private val alice = contact(1, "Alice")
    private val bob = contact(2, "Bob")

    @Before
    fun setUp() {
        app.resetUiScenario(
            contacts = listOf(alice, bob),
            groups = listOf(group("friends", "Friends", listOf(alice)))
        )
        grantAudioPermissionsForSdk()
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    @Test
    fun emptyStateOffersCreateAndValidatesBlankGroupName() {
        app.resetUiScenario(contacts = listOf(alice))

        launchMain().use {
            onView(withId(R.id.no_item_disclaimer)).check(matches(isDisplayed()))
            clickRoundedButton(R.id.btn_empty_add_group)
            clickRoundedButton(R.id.crbDone)

            onView(withText(R.string.enter_group_name)).check(matches(isDisplayed()))
            assertTrue(app.fakeContacts.createdNames.isEmpty())
        }
    }

    @Test
    fun groupDeleteSupportsCancelAndConfirm() {
        launchMain().use { scenario ->
            scenario.openDeleteConfirmation()
            clickDialogButton("button2")
            assertTrue(app.fakeContacts.deletedGroups.isEmpty())

            scenario.openDeleteConfirmation()
            clickDialogButton("button1")
            onView(allOf(withText(R.string.ok), isDisplayed())).perform(click())

            assertEquals(
                listOf("friends"),
                app.fakeContacts.deletedGroups.map { group -> group.id })
            onView(withText("Friends")).check(doesNotExist())
        }
    }

    @Test
    fun protectedGroupShowsDisabledDeleteAction() {
        app.resetUiScenario(
            contacts = listOf(alice),
            groups = listOf(group("protected", "Protected", listOf(alice), canDelete = false))
        )

        launchMain().use {
            onView(
                allOf(
                    withId(R.id.ctcibDelete),
                    hasSibling(withText("Protected"))
                )
            ).check(matches(allOf(isDisplayed(), not(isEnabled()))))
        }
    }

    @Test
    fun readOnlyGroupKeepsRingtoneActionAndDisablesStructuralActions() {
        val displayedName = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.read_only_group_name, "Protected")
        app.resetUiScenario(
            contacts = listOf(alice),
            groups = listOf(
                group(
                    id = "protected",
                    name = "Protected",
                    contacts = listOf(alice),
                    isReadOnly = true,
                    canModify = false,
                    canDelete = false,
                )
            )
        )
        runBlocking {
            app.homePreferencesStore.write(
                HomeDisplayPreferences(showReadOnlyGroups = true)
            )
        }

        launchMain().use {
            onView(allOf(withId(R.id.ctvGroupName), withText(displayedName)))
                .check(matches(isDisplayed()))
            onView(allOf(withId(R.id.ctcibManageContacts), hasSibling(withText(displayedName))))
                .check(matches(allOf(isDisplayed(), not(isEnabled()))))
            onView(allOf(withId(R.id.ctcibEdit), hasSibling(withText(displayedName))))
                .check(matches(allOf(isDisplayed(), not(isEnabled()))))
            onView(allOf(withId(R.id.ctcibDelete), hasSibling(withText(displayedName))))
                .check(matches(allOf(isDisplayed(), not(isEnabled()))))
            assertActionIconTint(R.id.ctcibManageContacts, R.color.home_classic_inactive_icon)
            assertActionIconTint(R.id.ctcibEdit, R.color.home_classic_inactive_icon)
            assertActionIconTint(R.id.ctcibDelete, R.color.home_classic_inactive_icon)
            onView(allOf(withId(R.id.crb_choose_ringtone), hasSibling(withText(displayedName))))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun manageMembersAddsSelectedContactAndPersistsRepositoryState() {
        launchMain().use { scenario ->
            scenario.clickGroupAction(R.id.ctcibManageContacts, "Friends", R.id.imageButton)
            onView(allOf(withText(R.string.confirm), isDisplayed())).perform(click())

            onView(allOf(withId(R.id.checkbox), hasSibling(withText("Bob"))))
                .perform(click())
            clickRoundedButton(R.id.crbDone)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            val savedNames = app.fakeContacts.memberUpdates.single().second.map { it.name }
            assertEquals(listOf("Alice", "Bob"), savedNames)
        }
    }

    @Test
    fun searchClearAndActivityRecreationRestoreTheCompleteGroupList() {
        app.fakeContacts.labelsFlow.value += group("work", "Work", listOf(bob))

        launchMain().use { scenario ->
            clickImageButton(R.id.ctcibToggleSearch)
            onView(allOf(withId(R.id.editTextInput), isDescendantOfA(withId(R.id.civGroupSearch))))
                .perform(typeText("Work"), pressImeActionButton(), closeSoftKeyboard())
            onView(allOf(withId(R.id.ctvGroupName), withText("Work")))
                .check(matches(isDisplayed()))
            onView(withText("Friends")).check(doesNotExist())

            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onView(allOf(withId(R.id.ctvGroupName), withText("Work")))
                .check(matches(isDisplayed()))

            clickImageButton(R.id.ctcibToggleSearch)
            onView(allOf(withId(R.id.ctvGroupName), withText("Friends")))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun resetAllRingtonesSupportsCancelAndConfirm() {
        app.fakeContacts.labelsFlow.value = listOf(
            group(
                id = "friends",
                name = "Friends",
                contacts = listOf(alice),
                ringtoneUris = listOf("content://tones/old"),
                ringtoneName = "Old tone"
            )
        )

        launchMain().use {
            openHomeMenuItem(R.string.reset_all_ringtones)
            onView(allOf(withText(R.string.cancel), isDisplayed())).perform(click())
            assertEquals(0, app.fakeContacts.clearAllRingtonesCount)

            openHomeMenuItem(R.string.reset_all_ringtones)
            onView(allOf(withText(R.string.confirm), isDisplayed())).perform(click())
            onView(allOf(withText(R.string.ok), isDisplayed())).perform(click())

            assertEquals(1, app.fakeContacts.clearAllRingtonesCount)
            assertTrue(app.fakeContacts.labelsFlow.value.single().ringtoneUriList.isEmpty())
        }
    }

    @Test
    fun sourcePickerChangesFromDeviceToCloudAccount() {
        val cloud = ContactSource.CloudAccount(AccountId.of("google", "friend@example.com"))
        app.fakeSources.available.value = listOf(ContactSource.OnDevice, cloud)

        launchMain().use {
            openHomeMenuItem(R.string.pick_contact_source_btn_label)
            onView(withText("friend@example.com (google)")).perform(click())
            onView(allOf(withText(R.string.ok), isDisplayed())).perform(click())

            assertEquals(cloud, app.fakeSources.selected.value)
        }
    }

    @Test
    fun preferencesApplyDescendingGroupSortingToTheRenderedList() {
        app.fakeContacts.labelsFlow.value = listOf(
            group("alpha", "Alpha", listOf(alice)),
            group("zulu", "Zulu", listOf(bob))
        )

        launchMain().use { scenario ->
            openHomeMenuItem(R.string.user_preferences)
            onView(withId(R.id.rbSortDescending)).perform(click())
            onView(allOf(withText(R.string.confirm), isDisplayed())).perform(click())
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.rwGroupItems)
                val firstName = list.layoutManager
                    ?.findViewByPosition(0)
                    ?.findViewById<android.widget.TextView>(R.id.ctvGroupName)
                    ?.text
                    ?.toString()
                assertEquals("Zulu", firstName)
            }
        }
    }

    @Test
    fun systemRingtonePickerResultUpdatesTheSelectedGroup() {
        val selectedUri = Uri.parse("content://media/internal/audio/media/42")
        val resultIntent = Intent().putExtra(
            RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
            selectedUri
        )
        intending(hasAction(RingtoneManager.ACTION_RINGTONE_PICKER))
            .respondWith(
                android.app.Instrumentation.ActivityResult(
                    Activity.RESULT_OK,
                    resultIntent
                )
            )

        launchMain().use { scenario ->
            scenario.clickGroupAction(R.id.crb_choose_ringtone, "Friends", R.id.btnMain)
            onView(allOf(withText(R.string.ringtone_source_system), isDisplayed())).perform(click())
            intended(hasAction(RingtoneManager.ACTION_RINGTONE_PICKER))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals(selectedUri.toString(), app.fakeContacts.ringtoneUpdates.single().second)
            onView(withText(R.string.everything_set)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun homeActionsAndGroupControlsExposeAccessibleDescriptions() {
        launchMain().use {
            onView(withId(R.id.ctcibActionsMenu)).check(matches(isDisplayed()))
            onView(withId(R.id.ctcibToggleSearch)).check(matches(isDisplayed()))
            onView(allOf(withId(R.id.ctvGroupName), withContentDescription("Friends")))
                .check(matches(isDisplayed()))

            val manageDescription = InstrumentationRegistry.getInstrumentation().targetContext
                .getString(
                    R.string.accessibility_group_action,
                    InstrumentationRegistry.getInstrumentation().targetContext
                        .getString(R.string.manage_contacts_group_name).replace("\n", " ").trim(),
                    "Friends"
                )
            onView(
                allOf(
                    withId(R.id.ctcibManageContacts),
                    withContentDescription(manageDescription)
                )
            )
                .check(matches(isDisplayed()))
        }
    }

    private fun openHomeMenuItem(textResId: Int) {
        clickImageButton(R.id.ctcibActionsMenu)
        onView(allOf(withText(textResId), isDisplayed(), isEnabled())).perform(click())
    }

    private fun ActivityScenario<MainActivity>.clickGroupAction(
        actionId: Int,
        groupName: String,
        clickableChildId: Int,
    ) {
        onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.rwGroupItems)
            val matchingItem = (0 until list.childCount)
                .map(list::getChildAt)
                .first { item ->
                    item.findViewById<android.widget.TextView>(R.id.ctvGroupName)
                        ?.text
                        ?.toString() == groupName
                }
            matchingItem.findViewById<android.view.View>(actionId)
                .findViewById<android.view.View>(clickableChildId)
                .performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun ActivityScenario<MainActivity>.openDeleteConfirmation() {
        onActivity { activity ->
            val navHost = activity.supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            val home = navHost.childFragmentManager.primaryNavigationFragment as HomeScreen
            home.onGroupDelete(app.fakeContacts.labelsFlow.value.single())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun clickDialogButton(resourceName: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val button = device.wait(
            Until.findObject(By.res("com.milen.grounpringtonesetter", resourceName)),
            3_000L
        ) ?: device.wait(
            Until.findObject(By.res("android", resourceName)),
            3_000L
        )
        if (button == null) {
            fail("Dialog button $resourceName was not displayed")
            return
        }
        button.click()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}

private fun assertActionIconTint(containerId: Int, colorResId: Int) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val expectedColor = ContextCompat.getColor(context, colorResId)
    onView(allOf(withId(R.id.imageButton), isDescendantOfA(withId(containerId))))
        .check { view, _ ->
            val actualColor = (view as ImageButton).imageTintList?.defaultColor
            assertEquals(expectedColor, actualColor)
        }
}
