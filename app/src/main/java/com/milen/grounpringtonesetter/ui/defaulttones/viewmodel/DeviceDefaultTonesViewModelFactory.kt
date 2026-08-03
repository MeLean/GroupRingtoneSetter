package com.milen.grounpringtonesetter.ui.defaulttones.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.milen.grounpringtonesetter.App

internal object DeviceDefaultTonesViewModelFactory {
    fun provideFactory(activity: FragmentActivity): ViewModelProvider.Factory {
        val app = activity.application as App
        val toneManager = app.provideDefaultToneManager()
        val tracker = app.tracker
        val entitlementState = app.billingManager.state

        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DeviceDefaultTonesViewModel(
                    onError = tracker::trackError,
                    toneManager = toneManager,
                    entitlementState = entitlementState
                ) as T
            }
        }
    }
}
