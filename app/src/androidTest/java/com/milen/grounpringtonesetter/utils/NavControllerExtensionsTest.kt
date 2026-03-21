package com.milen.grounpringtonesetter.utils

import androidx.lifecycle.ViewModelStore
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.ui.picker.PickerScreenFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavControllerExtensionsTest {

    @Test
    fun navigateIfCurrentDestinationNavigatesFromHomeToPicker() {
        val navController = runOnMainSync { createNavController() }
        var failure: GuardedNavigationFailure? = null
        var currentDestinationId: Int? = null

        runOnMainSync {
            navController.setCurrentDestination(R.id.homeFragment)
            failure = navController.navigateIfCurrentDestination(
                expectedDestinationId = R.id.homeFragment,
                actionId = R.id.action_home_to_picker,
                args = PickerScreenFragment.argsForCreate()
            )
            currentDestinationId = navController.currentDestination?.id
        }

        assertNull(failure)
        assertEquals(R.id.pickerFragment, currentDestinationId)
    }

    @Test
    fun navigateIfCurrentDestinationReturnsWrongDestinationFromNoInternet() {
        val navController = runOnMainSync { createNavController() }
        var failure: GuardedNavigationFailure? = null
        var currentDestinationId: Int? = null

        runOnMainSync {
            navController.setCurrentDestination(R.id.noInternetFragment)
            failure = navController.navigateIfCurrentDestination(
                expectedDestinationId = R.id.homeFragment,
                actionId = R.id.action_home_to_picker,
                args = PickerScreenFragment.argsForCreate()
            )
            currentDestinationId = navController.currentDestination?.id
        }

        assertEquals(GuardedNavigationFailureReason.WRONG_DESTINATION, failure?.reason)
        assertEquals(R.id.noInternetFragment, currentDestinationId)
    }

    private fun createNavController(): TestNavHostController {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return TestNavHostController(context).apply {
            setViewModelStore(ViewModelStore())
            setGraph(R.navigation.nav_graph)
        }
    }

    private fun <T> runOnMainSync(block: () -> T): T {
        var result: T? = null
        var error: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                result = block()
            } catch (throwable: Throwable) {
                error = throwable
            }
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
