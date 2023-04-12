package com.milen.grounpringtonesetter.ui.callbacks

import android.content.ContentResolver
import android.net.Uri
import com.milen.grounpringtonesetter.data.GroupItem
import com.milen.grounpringtonesetter.screens.home.HomeScreenState
import kotlinx.coroutines.flow.StateFlow

data class HomeViewModelCallbacks(
    val uiState: StateFlow<HomeScreenState>,
    val fetchLabels: (ContentResolver) -> Unit,
    val onSetRingtones: (MutableList<GroupItem>, ContentResolver, Boolean) -> Unit,
    val onRingtoneChosen: (String, Uri?) -> Unit
)
