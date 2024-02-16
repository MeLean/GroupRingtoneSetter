package com.milen.grounpringtonesetter.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.milen.grounpringtonesetter.utils.ContactsHelper

class MainViewModelFactory(private val contactsHelper: ContactsHelper) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(contactsHelper) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}