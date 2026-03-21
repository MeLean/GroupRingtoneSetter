package com.milen.grounpringtonesetter.ui.nointernet

import androidx.lifecycle.ViewModelStore
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.milen.grounpringtonesetter.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoInternetNavigationTest {

    @Test
    fun reconnectPopsNoInternetScreenBackToHome() {
        val navController = runOnMainSync { createNavController() }
        var beforePopDestinationId: Int? = null
        var popped = false
        var afterPopDestinationId: Int? = null

        runOnMainSync {
            navController.setCurrentDestination(R.id.homeFragment)
            navController.navigate(R.id.action_home_to_noInternet)
            beforePopDestinationId = navController.currentDestination?.id
            popped = navController.popBackFromNoInternetScreen()
            afterPopDestinationId = navController.currentDestination?.id
        }

        assertEquals(R.id.noInternetFragment, beforePopDestinationId)
        assertTrue(popped)
        assertEquals(R.id.homeFragment, afterPopDestinationId)
    }

    @Test
    fun reconnectHelperDoesNothingOutsideNoInternet() {
        val navController = runOnMainSync { createNavController() }
        var popped = false
        var currentDestinationId: Int? = null

        runOnMainSync {
            navController.setCurrentDestination(R.id.homeFragment)
            popped = navController.popBackFromNoInternetScreen()
            currentDestinationId = navController.currentDestination?.id
        }

        assertFalse(popped)
        assertEquals(R.id.homeFragment, currentDestinationId)
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
