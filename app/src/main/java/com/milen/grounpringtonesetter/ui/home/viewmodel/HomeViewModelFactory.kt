package com.milen.grounpringtonesetter.ui.home.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.milen.grounpringtonesetter.App

internal object HomeViewModelFactory {

    fun provideFactory(activity: FragmentActivity): ViewModelProvider.Factory {
        val app = activity.application as App
        val tracker = app.tracker
        val billing = app.billingManager
        val contactsRepo = app.provideContactsRepository()
        val ads = app.provideInterstitialAdGateway(activity, placement = "home_interstitial")

        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(
                    adHelper = ads,
                    tracker = tracker,
                    billing = billing,
                    contactsRepo = contactsRepo,
                    sourceRepo = app.provideContactSourceRepository(),
                    homePreferencesStore = app.homePreferencesStore
                ) as T
            }
        }
    }
}
