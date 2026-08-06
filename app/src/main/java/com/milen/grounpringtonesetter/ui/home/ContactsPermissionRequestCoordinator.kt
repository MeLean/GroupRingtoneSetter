package com.milen.grounpringtonesetter.ui.home

internal class ContactsPermissionRequestCoordinator {

    private var isRequestInFlight = false

    fun shouldLaunchRequest(arePermissionsGranted: Boolean): Boolean {
        if (arePermissionsGranted || isRequestInFlight) return false

        isRequestInFlight = true
        return true
    }

    fun onRequestResult(
        permissionResults: Map<String, Boolean>,
        arePermissionsGranted: Boolean,
        shouldShowRationale: Boolean,
    ): ContactsPermissionRequestResult {
        isRequestInFlight = false
        return when {
            permissionResults.isEmpty() -> ContactsPermissionRequestResult.NotRequested
            arePermissionsGranted -> ContactsPermissionRequestResult.Granted
            shouldShowRationale -> ContactsPermissionRequestResult.RationaleRequired
            else -> ContactsPermissionRequestResult.PermanentlyRefused
        }
    }
}

internal enum class ContactsPermissionRequestResult {
    Granted,
    RationaleRequired,
    PermanentlyRefused,
    NotRequested,
}
