package com.milen.grounpringtonesetter.screens.home

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.milen.grounpringtonesetter.data.GroupItem
import kotlinx.coroutines.flow.StateFlow

data class HomeViewModelCallbacks(
    val uiState: StateFlow<HomeScreenState>,
    val onCreateGroup: (String?) -> Unit,
    val onSetRingtones: (List<GroupItem>, ContentResolver) -> Unit,
    val onRingtoneChosen: (Long, Uri?) -> Unit,
    val onGroupDeleted: (Long) -> Unit,
    val setUpGroupNamePicking: (GroupItem) -> Unit,
    val setUpContactsPicking: (GroupItem) -> Unit,
    val fetchGroups: () -> Unit,
    val hideLoading: () -> Unit,
    val loadAd: (Context, String) -> Unit,
    val showAd: (Activity) -> Unit
)