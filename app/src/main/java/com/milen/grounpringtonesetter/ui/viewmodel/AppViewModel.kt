package com.milen.grounpringtonesetter.ui.viewmodel

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.data.repos.ContactsRepository
import com.milen.grounpringtonesetter.data.repos.RepoGraph
import com.milen.grounpringtonesetter.utils.ContactRingtoneUpdateHelper
import com.milen.grounpringtonesetter.utils.ContactsHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

internal class AppViewModel(
    private val app: Application,
    private val contactsRepo: ContactsRepository,
) : ViewModel() {

    // NEW: fire-and-forget signal that “contacts changed on device”
    private val _contactsDirty = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val contactsDirty: Flow<Unit> get() = _contactsDirty

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            contactsRepo.invalidate()
            _contactsDirty.tryEmit(Unit) // signal screens to run SWR (updateGroupList)
        }
    }

    init {
        app.contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI, true, observer
        )
        app.contentResolver.registerContentObserver(
            ContactsContract.Data.CONTENT_URI, true, observer
        )
    }

    override fun onCleared() {
        app.contentResolver.unregisterContentObserver(observer)
        super.onCleared()
    }
}

internal object AppViewModelFactory {
    fun provideFactory(activity: FragmentActivity): ViewModelProvider.Factory {
        val app = activity.application as App
        val tracker = app.tracker
        val prefs = EncryptedPreferencesHelper(activity.application)
        val contactsHelper = ContactsHelper(
            appContext = activity.application,
            preferenceHelper = prefs,
            contactRingtoneUpdateHelper = ContactRingtoneUpdateHelper(
                tracker = tracker,
                preferenceHelper = prefs
            ),
            tracker = tracker
        )
        val repo = RepoGraph.contacts(activity.application, contactsHelper, tracker)

        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AppViewModel(activity.application, repo) as T
            }
        }
    }
}