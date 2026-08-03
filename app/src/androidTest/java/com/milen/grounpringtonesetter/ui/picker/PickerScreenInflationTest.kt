package com.milen.grounpringtonesetter.ui.picker

import android.Manifest
import android.view.View
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.milen.grounpringtonesetter.MainActivity
import com.milen.grounpringtonesetter.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
