package com.milen.grounpringtonesetter.ui.picker

import android.Manifest
import android.os.Parcel
import android.view.View
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.milen.grounpringtonesetter.MainActivity
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PickerScreenInflationTest {

    @get:Rule
    val contactsPermissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
    )

    @Test
    fun groupNavigationArgumentsStaySmallWithManyContacts() {
        val group = LabelItem(
            id = "group-1",
            groupName = "Friends",
            contacts = (1L..1_500L).map { id ->
                Contact(id, "lookup-$id", "Contact $id", "+359$id", null)
            },
        )

        listOf(
            PickerScreenFragment.argsForManage(group),
            PickerScreenFragment.argsForRename(group),
        ).forEach { args ->
            val parcel = Parcel.obtain()
            try {
                args.writeToParcel(parcel, 0)
                assertTrue("Picker arguments contain contact data", parcel.dataSize() < 2_048)
            } finally {
                parcel.recycle()
            }
        }
    }

    @Test
    fun createFlowInflatesPickerScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val navHostFragment = activity.supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                navHostFragment.navController.navigate(
                    R.id.action_home_to_picker,
                    PickerScreenFragment.argsForCreate()
                )
            }

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            var currentDestinationId: Int? = null
            var contactsView: View? = null
            scenario.onActivity { activity ->
                val navHostFragment = activity.supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                currentDestinationId = navHostFragment.navController.currentDestination?.id
                contactsView = activity.findViewById(R.id.scvContacts)
            }

            assertEquals(R.id.pickerFragment, currentDestinationId)
            assertNotNull(contactsView)
        }
    }
}
