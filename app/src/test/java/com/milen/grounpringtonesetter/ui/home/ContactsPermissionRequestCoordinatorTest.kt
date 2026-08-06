package com.milen.grounpringtonesetter.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsPermissionRequestCoordinatorTest {

    @Test
    fun `missing permissions launches only one request until it completes`() {
        val coordinator = ContactsPermissionRequestCoordinator()

        assertTrue(coordinator.shouldLaunchRequest(arePermissionsGranted = false))
        assertFalse(coordinator.shouldLaunchRequest(arePermissionsGranted = false))
    }

    @Test
    fun `empty result is not treated as a permission refusal`() {
        val coordinator = ContactsPermissionRequestCoordinator()
        coordinator.shouldLaunchRequest(arePermissionsGranted = false)

        assertEquals(
            ContactsPermissionRequestResult.NotRequested,
            coordinator.onRequestResult(
                permissionResults = emptyMap(),
                arePermissionsGranted = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `first contacts or audio permission denial requests a rationale instead of the terminal error`() {
        val coordinator = ContactsPermissionRequestCoordinator()
        coordinator.shouldLaunchRequest(arePermissionsGranted = false)

        assertEquals(
            ContactsPermissionRequestResult.RationaleRequired,
            coordinator.onRequestResult(
                permissionResults = mapOf("android.permission.READ_CONTACTS" to false),
                arePermissionsGranted = false,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun `permanently denied permission shows the terminal error`() {
        val coordinator = ContactsPermissionRequestCoordinator()
        coordinator.shouldLaunchRequest(arePermissionsGranted = false)

        assertEquals(
            ContactsPermissionRequestResult.PermanentlyRefused,
            coordinator.onRequestResult(
                permissionResults = mapOf("android.permission.READ_CONTACTS" to false),
                arePermissionsGranted = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `granted result allows a later request if permission is revoked`() {
        val coordinator = ContactsPermissionRequestCoordinator()
        coordinator.shouldLaunchRequest(arePermissionsGranted = false)

        assertEquals(
            ContactsPermissionRequestResult.Granted,
            coordinator.onRequestResult(
                permissionResults = mapOf("android.permission.READ_CONTACTS" to true),
                arePermissionsGranted = true,
                shouldShowRationale = false,
            ),
        )
        assertTrue(coordinator.shouldLaunchRequest(arePermissionsGranted = false))
    }
}
