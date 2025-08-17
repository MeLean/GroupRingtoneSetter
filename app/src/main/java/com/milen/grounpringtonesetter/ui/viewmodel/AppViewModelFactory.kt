package com.milen.grounpringtonesetter.ui.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.customviews.dialog.DialogShower
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.data.repos.RepoGraph
import com.milen.grounpringtonesetter.utils.ContactRingtoneUpdateHelper
import com.milen.grounpringtonesetter.utils.ContactsHelper

internal object AppViewModelFactory {
    fun provideFactory(activity: FragmentActivity): ViewModelProvider.Factory {
        val app = activity.application as App
        val tracker = app.tracker
        val prefs = EncryptedPreferencesHelper(activity.application)
        DialogShower(activity)
        val contactsHelper = ContactsHelper(
            appContext = activity.application,
            preferenceHelper = prefs,
            contactRingtoneUpdateHelper = ContactRingtoneUpdateHelper(
                tracker = tracker,
                preferenceHelper = prefs
            ),
            tracker = tracker
        )
        val repo = RepoGraph.contacts(
            activity.application,
            contactsHelper,
            tracker,
            prefs
        )

        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AppViewModel(
                    app = activity.application,
                    contactsRepo = repo,
                ) as T
            }
        }
    }
}