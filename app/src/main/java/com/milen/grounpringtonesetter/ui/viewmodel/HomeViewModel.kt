package com.milen.grounpringtonesetter.ui.viewmodel

import android.app.Activity
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.ui.home.HomeEvent
import com.milen.grounpringtonesetter.ui.home.HomeScreenState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin delegating VM so HomeScreen can be decoupled without changing behavior.
 * IMPORTANT: Signatures MUST mirror MainViewModel exactly.
 */
internal class HomeViewModel(
    private val main: MainViewModel,
) : ViewModel() {

    val state: StateFlow<HomeScreenState> get() = main.state

    val events: Flow<HomeEvent> get() = main.events
    var selectingGroup: LabelItem?
        get() = main.selectingGroup
        set(value) {
            if (value != null) {
                main.selectingGroup = value
            }
        }

    fun onPermissionsGranted() = main.onPermissionsGranted()
    fun onPermissionsRefused() = main.onPermissionsRefused()
    fun onNoPermissions() = main.onNoPermissions()
    fun onConnectionChanged(isConnected: Boolean) = main.onConnectionChanged(isConnected)

    fun setUpGroupNameEditing(group: LabelItem) = main.setUpGroupNameEditing(group)
    fun setUpContactsManaging(group: LabelItem) = main.setUpContactsManaging(group)
    fun setUpGroupCreateRequest() = main.setUpGroupCreateRequest()

    fun onSetAllGroupsRingtones() = main.onSetAllGroupsRingtones()
    fun onApplySingleRingtone(labelItem: LabelItem) = main.onApplySingleRingtone(labelItem)
    fun onGroupDeleted(labelItem: LabelItem) = main.onGroupDeleted(labelItem)

    fun onRingtoneChosen(uri: Uri, fileName: String) = main.onRingtoneChosen(uri, fileName)

    fun startPurchase(activity: Activity) = main.startPurchase(activity)

    fun trackNoneFatal(error: Exception) = main.trackNoneFatal(error)
    fun onAccountsSelected(selectedAccounts: Set<String>?) =
        main.onAccountsSelected(selectedAccounts)
}

internal object HomeViewModelFactory {
    fun provideFactory(activity: FragmentActivity): ViewModelProvider.Factory {
        val mainVm = ViewModelProvider(
            activity,
            MainViewModelFactory.provideFactory(activity)
        ).get(MainViewModel::class.java)

        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(mainVm) as T
            }
        }
    }
}