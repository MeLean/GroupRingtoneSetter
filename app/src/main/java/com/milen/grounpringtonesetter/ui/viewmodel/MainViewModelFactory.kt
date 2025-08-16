package com.milen.grounpringtonesetter.ui.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.customviews.dialog.DialogShower
import com.milen.grounpringtonesetter.customviews.ui.ads.AdLoadingHelper
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.data.repos.RepoGraph
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
                    val app = activity.application as App
                    val preferenceHelper = EncryptedPreferencesHelper(app = app)
                    val tracker = app.tracker
                    val billing = app.billingManager
                    val contactsHelper = ContactsHelper(
                        appContext = activity.application,
                        preferenceHelper = preferenceHelper,
                        contactRingtoneUpdateHelper = ContactRingtoneUpdateHelper(
                            tracker = tracker,
                            preferenceHelper = preferenceHelper
                        ),
                        tracker = tracker
                    )
                    val contactsRepo =
                        RepoGraph.contacts(activity.application, contactsHelper, tracker)

                    return MainViewModel(
                        appContext = app,
                        adHelper = AdLoadingHelper(activity),
                        dialogShower = DialogShower(activity),
                        contactsHelper = contactsHelper,
                        encryptedPrefs = preferenceHelper,
                        tracker = tracker,
                        billing = billing,
                        contactsRepo = contactsRepo
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }
}