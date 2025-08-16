package com.milen.grounpringtonesetter.data.repos

import android.app.Application
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.Tracker

internal object RepoGraph {
    @Volatile
    private var repo: ContactsRepository? = null

    fun contacts(app: Application, helper: ContactsHelper, tracker: Tracker): ContactsRepository {
        return repo ?: synchronized(this) {
            repo ?: ContactsRepositoryImpl(app, helper, tracker).also { repo = it }
        }
    }
}