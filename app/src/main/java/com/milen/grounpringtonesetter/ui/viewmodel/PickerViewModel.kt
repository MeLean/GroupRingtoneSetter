package com.milen.grounpringtonesetter.ui.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

internal class PickerViewModel(
    internal val main: MainViewModel,
) : ViewModel()

internal object PickerViewModelFactory {
    fun provideFactory(activity: FragmentActivity): ViewModelProvider.Factory {
        val mainVm = ViewModelProvider(
            activity,
            MainViewModelFactory.provideFactory(activity)
        )[MainViewModel::class.java]

        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PickerViewModel(mainVm) as T
            }
        }
    }
}