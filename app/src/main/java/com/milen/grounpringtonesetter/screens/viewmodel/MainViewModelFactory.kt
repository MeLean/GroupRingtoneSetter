package com.milen.grounpringtonesetter.screens.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.customviews.dialog.DialogShower
import com.milen.grounpringtonesetter.customviews.ui.ads.AdLoadingHelper
import com.milen.grounpringtonesetter.data.exceptions.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.utils.ContactRingtoneUpdateHelper
import com.milen.grounpringtonesetter.utils.ContactsHelper

object MainViewModelFactory {
    fun provideFactory(
        activity: Activity,
    ): ViewModelProvider.Factory {
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    val preferenceHelper = EncryptedPreferencesHelper(
                        app = activity.application,
                    )
                    val tracker = (activity.application as App).tracker
                    val billing = (activity.application as App).billingManager

                    return MainViewModel(
                        adHelper = AdLoadingHelper(activity),
                        dialogShower = DialogShower(activity),
                        contactsHelper = ContactsHelper(
                            appContext = activity.application,
                            preferenceHelper = preferenceHelper,
                            contactRingtoneUpdateHelper = ContactRingtoneUpdateHelper(
                                tracker = tracker,
                                preferenceHelper = preferenceHelper
                            ),
                            tracker = tracker
                        ),
                        encryptedPrefs = preferenceHelper,
                        tracker = tracker,
                        billing = billing,
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }
}