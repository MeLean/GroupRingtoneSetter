package com.milen.grounpringtonesetter.ui

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.milen.grounpringtonesetter.MainActivity
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.sources.ContactSource
import com.milen.grounpringtonesetter.testing.RegressionTestApplication
import org.hamcrest.Matchers.allOf

internal val contactsPermissionRule = androidx.test.rule.GrantPermissionRule.grant(
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.WRITE_CONTACTS,
)

internal fun regressionApp(): RegressionTestApplication =
    InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as RegressionTestApplication

internal fun RegressionTestApplication.resetUiScenario(
    contacts: List<Contact> = emptyList(),
    groups: List<LabelItem> = emptyList(),
) {
    fakeContacts.allContacts.value = contacts
    fakeContacts.labelsFlow.value = groups
    fakeContacts.createdNames.clear()
    fakeContacts.renamedGroups.clear()
    fakeContacts.deletedGroups.clear()
    fakeContacts.memberUpdates.clear()
    fakeContacts.ringtoneUpdates.clear()
    fakeContacts.clearAllRingtonesCount = 0
    fakeSources.selected.value = ContactSource.OnDevice
    fakeSources.available.value = listOf(ContactSource.OnDevice)
    fakeBilling.purchaseLaunchCount = 0
    fakeBilling.state.value = com.milen.grounpringtonesetter.billing.EntitlementState.OWNED
    fakeAds.setCanLoadAds(false)
    fakeAds.setPrivacyOptionsRequired(false)
    fakeDefaultTones.canWrite = true
    fakeDefaultTones.appliedTones.clear()
    fakeDefaultTones.deletedImportedTones.clear()
}

internal fun launchMain(): ActivityScenario<MainActivity> =
    ActivityScenario.launch(MainActivity::class.java)

internal fun ActivityScenario<MainActivity>.navigate(actionId: Int) {
    onActivity { activity ->
        val navHost = activity.supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navHost.navController.navigate(actionId)
    }
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
}

internal fun clickRoundedButton(containerId: Int) {
    onView(allOf(withId(R.id.btnMain), isDescendantOfA(withId(containerId)))).perform(click())
}

internal fun clickImageButton(containerId: Int) {
    onView(allOf(withId(R.id.imageButton), isDescendantOfA(withId(containerId)))).perform(click())
}

internal fun grantAudioPermissionsForSdk() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val packageName = instrumentation.targetContext.packageName
    val permissions = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            listOf(Manifest.permission.READ_MEDIA_AUDIO)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
    }
    permissions.forEach { permission ->
        val output = instrumentation.uiAutomation
            .executeShellCommand("pm grant $packageName $permission")
        ParcelFileDescriptor.AutoCloseInputStream(output).use { stream ->
            stream.readBytes()
        }
    }
}

internal fun contact(
    id: Long,
    name: String,
    ringtone: String? = null,
): Contact = Contact(
    id = id,
    lookupKey = "lookup-$id",
    name = name,
    phone = "+359100000${id.toString().padStart(3, '0')}",
    ringtoneUriStr = ringtone,
)

internal fun group(
    id: String,
    name: String,
    contacts: List<Contact> = emptyList(),
    ringtoneUris: List<String> = emptyList(),
    ringtoneName: String = "",
    canDelete: Boolean = true,
): LabelItem = LabelItem(
    id = id,
    groupName = name,
    contacts = contacts,
    ringtoneUriList = ringtoneUris,
    ringtoneFileName = ringtoneName,
    canDelete = canDelete,
)
