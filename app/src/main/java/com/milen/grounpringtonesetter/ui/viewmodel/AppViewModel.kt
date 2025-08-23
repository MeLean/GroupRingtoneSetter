package com.milen.grounpringtonesetter.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.milen.grounpringtonesetter.data.repos.ContactsRepository
import com.milen.grounpringtonesetter.utils.launch

internal class AppViewModel(
    private val app: Application,
    private val contactsRepo: ContactsRepository,

) : ViewModel() {

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            update()
        }
    }
    
    @Volatile
    private var observersRegistered = false

    fun start() {
        if (observersRegistered) return
        if (!hasContactsPermission(app)) return
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

    private fun hasContactsPermission(ctx: Context): Boolean {
        val r = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
        val w = ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CONTACTS)
        return r == PackageManager.PERMISSION_GRANTED || w == PackageManager.PERMISSION_GRANTED
    }

    private fun update() {
        launch {
            contactsRepo.load()
        }
    }
}