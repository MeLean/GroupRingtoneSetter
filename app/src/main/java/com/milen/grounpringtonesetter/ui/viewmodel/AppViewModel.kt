package com.milen.grounpringtonesetter.ui.viewmodel

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.lifecycle.ViewModel
import com.milen.grounpringtonesetter.data.repos.ContactsRepository

internal class AppViewModel(
    private val app: Application,
    private val contactsRepo: ContactsRepository,

) : ViewModel() {

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            contactsRepo.invalidate()
        }
    }

    fun start() {
        removeContactsObserver()

        app.contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI, true, observer
        )
        app.contentResolver.registerContentObserver(
            ContactsContract.Data.CONTENT_URI, true, observer
        )
    }

    override fun onCleared() {
        removeContactsObserver()
        super.onCleared()
    }

    fun removeContactsObserver() {
        app.contentResolver.unregisterContentObserver(observer)
    }
}