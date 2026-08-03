package com.milen.grounpringtonesetter.ui.picker.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.milen.grounpringtonesetter.App

internal object PickerViewModelFactory {

    fun provideFactory(activity: FragmentActivity): ViewModelProvider.Factory {
        val app = activity.application as App
        val tracker = app.tracker

        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PickerViewModel(
                    tracker = tracker,
                    contactsRepo = app.provideContactsRepository()
                ) as T
            }
        }
    }
}
