package com.milen.grounpringtonesetter.screens.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.milen.grounpringtonesetter.customviews.dialog.DialogShower
import com.milen.grounpringtonesetter.customviews.ui.ads.AdLoadingHelper
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.EncryptedPreferencesHelper

object MainViewModelFactory {
    fun provideFactory(
        activity: Activity,
    ): ViewModelProvider.Factory {
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    return MainViewModel(
                        adHelper = AdLoadingHelper(activity),
                        dialogShower = DialogShower(activity),
                        contactsHelper = ContactsHelper(appContext = activity.application),
                        encryptedPrefs = EncryptedPreferencesHelper(appContext = activity.application)
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }
}