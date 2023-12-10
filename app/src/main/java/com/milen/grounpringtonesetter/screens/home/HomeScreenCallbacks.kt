package com.milen.grounpringtonesetter.screens.home

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.milen.grounpringtonesetter.data.GroupItem
import kotlinx.coroutines.flow.StateFlow

data class HomeViewModelCallbacks(
    val uiState: StateFlow<HomeScreenState>,
    val fetchLabels: (ContentResolver) -> Unit,
    val onSetRingtones: (MutableList<GroupItem>, ContentResolver) -> Unit,
    val onRingtoneChosen: (String, Uri?) -> Unit,
    val hideLoading: () -> Unit,
    val loadAd: (Context, String) -> Unit,
    val showAd: (Activity) -> Unit
)