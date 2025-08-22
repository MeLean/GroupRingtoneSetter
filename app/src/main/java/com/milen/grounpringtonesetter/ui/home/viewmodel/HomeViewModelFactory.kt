package com.milen.grounpringtonesetter.ui.home.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.actions.GroupActions
import com.milen.grounpringtonesetter.customviews.ui.ads.AdLoadingHelper
import com.milen.grounpringtonesetter.data.accounts.AccountsResolver
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.data.repos.RepoGraph
import com.milen.grounpringtonesetter.utils.ContactRingtoneUpdateHelper
import com.milen.grounpringtonesetter.utils.ContactsHelper

internal object HomeViewModelFactory {

    fun provideFactory(activity: FragmentActivity): ViewModelProvider.Factory {
        val app = activity.application as App
        val tracker = app.tracker
        val billing = app.billingManager

        AccountsResolver(app)
        val prefs = EncryptedPreferencesHelper(app)

        val ringtoneUpdater = ContactRingtoneUpdateHelper(
            tracker = tracker,
            preferenceHelper = prefs
        )

        val contactsHelper = ContactsHelper(
            appContext = app,
            preferenceHelper = prefs,
            contactRingtoneUpdateHelper = ringtoneUpdater,
            tracker = tracker
        )

        val contactsRepo = RepoGraph.contacts(
            app = app,
            helper = contactsHelper,
            prefs = prefs
        )

        val actions = GroupActions(
            contacts = contactsHelper,
            tracker = tracker,
            accountRepo = RepoGraph.accountRepo(app, prefs)
        )

        val ads = AdLoadingHelper(activity)

        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(
                    adHelper = ads,
                    encryptedPrefs = prefs,
                    tracker = tracker,
                    billing = billing,
                    actions = actions,
                    contactsRepo = contactsRepo,
                    accountRepo = RepoGraph.accountRepo(activity.application, prefs)
                ) as T
            }
        }
    }
}