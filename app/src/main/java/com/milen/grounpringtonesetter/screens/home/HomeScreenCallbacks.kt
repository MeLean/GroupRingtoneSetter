package com.milen.grounpringtonesetter.screens.home

import android.content.ContentResolver
import android.net.Uri
import com.milen.grounpringtonesetter.data.GroupItem
import kotlinx.coroutines.flow.StateFlow

data class HomeViewModelCallbacks(
    val uiState: StateFlow<HomeScreenState>,
    val fetchLabels: (ContentResolver) -> Unit,
    val onSetRingtones: (MutableList<GroupItem>, ContentResolver, Boolean) -> Unit,
    val onRingtoneChosen: (String, Uri?) -> Unit,
    val hideLoading: () -> Unit,
    val showAd: () -> Unit
)