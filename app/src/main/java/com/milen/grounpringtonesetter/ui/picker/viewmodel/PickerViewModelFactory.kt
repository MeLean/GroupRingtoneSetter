package com.milen.grounpringtonesetter.ui.picker.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.actions.GroupActions
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.utils.ContactRingtoneUpdateHelper
import com.milen.grounpringtonesetter.utils.ContactsHelper

internal object PickerViewModelFactory {

    fun provideFactory(activity: FragmentActivity): ViewModelProvider.Factory {
        val app = activity.application as App
        val tracker = app.tracker

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
        val actions = GroupActions(contactsHelper, tracker)

        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PickerViewModel(
                    contactsHelper = contactsHelper,
                    actions = actions,
                    tracker = tracker,
                    encryptedPrefs = prefs,
                ) as T
            }
        }
    }
}