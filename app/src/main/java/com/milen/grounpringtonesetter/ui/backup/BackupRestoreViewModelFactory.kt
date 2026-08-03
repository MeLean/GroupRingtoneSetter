package com.milen.grounpringtonesetter.ui.backup

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.backup.BackupRestoreRepository
import com.milen.grounpringtonesetter.backup.BackupSnapshotBuilder
import com.milen.grounpringtonesetter.backup.GrsArchiveReader
import com.milen.grounpringtonesetter.backup.GrsArchiveWriter
import com.milen.grounpringtonesetter.backup.RestoreExecutor
import com.milen.grounpringtonesetter.backup.RestorePlanner
import com.milen.grounpringtonesetter.data.local.EncryptedLocalLabelsDataSource
import com.milen.grounpringtonesetter.data.local.LocalLabelsStore
import com.milen.grounpringtonesetter.data.repos.RepoGraph
import com.milen.grounpringtonesetter.ui.defaulttones.AndroidDeviceDefaultToneManager
import com.milen.grounpringtonesetter.utils.ContactRingtoneUpdateHelper
import com.milen.grounpringtonesetter.utils.ContactsHelper

internal object BackupRestoreViewModelFactory {

    fun provideFactory(activity: FragmentActivity): ViewModelProvider.Factory {
        val app = activity.application as App
        val tracker = app.tracker
        val prefs = app.preferencesHelper
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
        val sourceRepo = RepoGraph.contactSourceRepo(app, contactsHelper, prefs)
        val localLabelsStore = LocalLabelsStore(
            dataSource = EncryptedLocalLabelsDataSource(prefs)
        )
        val defaultToneManager = AndroidDeviceDefaultToneManager(app)
        val repository = BackupRestoreRepository(
            snapshotBuilder = BackupSnapshotBuilder(
                context = app,
                contactsHelper = contactsHelper,
                localLabelsStore = localLabelsStore,
                defaultToneManager = defaultToneManager
            ),
            archiveWriter = GrsArchiveWriter(app),
            archiveReader = GrsArchiveReader(),
            restorePlanner = RestorePlanner(),
            restoreExecutor = RestoreExecutor(
                context = app,
                contactsHelper = contactsHelper,
                localLabelsStore = localLabelsStore,
                defaultToneManager = defaultToneManager,
                tracker = tracker
            ),
            contactsHelper = contactsHelper,
            sourceRepository = sourceRepo,
            defaultToneManager = defaultToneManager,
            tracker = tracker
        )

        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return BackupRestoreViewModel(
                    documents = ContentResolverBackupDocumentGateway(app.contentResolver),
                    repository = repository,
                    billing = app.billingManager,
                    tracker = tracker
                ) as T
            }
        }
    }
}
