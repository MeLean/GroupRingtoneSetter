package com.milen.grounpringtonesetter.screens.home

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.composables.eventobservers.InternetConnectivity
import com.milen.grounpringtonesetter.composables.ui.ads.BannerAdView
import com.milen.grounpringtonesetter.composables.ui.buttons.RoundCornerButton
import com.milen.grounpringtonesetter.composables.ui.screens.FullScreenLoading
import com.milen.grounpringtonesetter.composables.ui.screens.FullscreenImageWithCenteredContent
import com.milen.grounpringtonesetter.composables.ui.texts.CenteredTextWithButtonScreen
import com.milen.grounpringtonesetter.composables.ui.widgets.GroupItemRow
import com.milen.grounpringtonesetter.composables.ui.widgets.TransparentScaffold
import com.milen.grounpringtonesetter.data.GroupItem
import com.milen.grounpringtonesetter.navigation.Destination
import com.milen.grounpringtonesetter.ui.composables.RowWithEndButton
import com.milen.grounpringtonesetter.utils.areAllPermissionsGranted
import com.milen.grounpringtonesetter.utils.audioPermissionSdkBased


@Composable
fun HomeScreen(
    callbacks: HomeViewModelCallbacks,
    navigate: (String) -> Unit,
    onFinish: () -> Unit
) {
    val screenState by callbacks.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity
    val permissions = mutableListOf(
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_CONTACTS,
    ).also {
        it.add(audioPermissionSdkBased())
    }

    val launcherMultiplePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        when {
            activity.areAllPermissionsGranted(permissions) -> callbacks.fetchGroups()
            else -> callbacks.hideLoading()
        }
    }

    InternetConnectivity(
        onConnectionLost = { navigate(Destination.NO_INTERNET.route) }
    )

    when {
        screenState.isLoading -> FullScreenLoading()
        screenState.groupItems.isEmpty() ->
            FullscreenImageWithCenteredContent {
                CenteredTextWithButtonScreen(
                    text = stringResource(R.string.groups_not_found),
                    btnLabel = stringResource(R.string.close_app),
                    onClick = onFinish,
                    onClose = onFinish
                )
            }

        screenState.isAllDone ->
            FullscreenImageWithCenteredContent {
                CenteredTextWithButtonScreen(
                    text = stringResource(R.string.everything_set),
                    btnLabel = stringResource(R.string.close_app),
                    onClick = onFinish,
                    onClose = onFinish
                ).also {
                    callbacks.showAd(activity)
                }
            }

        else ->
            FullscreenImageWithCenteredContent {
                LabelsList(
                    screenState = screenState,
                    onRingtoneChosen = callbacks.onRingtoneChosen,
                    onSetRingtones = callbacks.onSetRingtones,
                    createGroup = callbacks.onCreateGroup,
                    onGroupDeleted = callbacks.onGroupDeleted,
                    setUpGroupNamePicking = callbacks.setUpGroupNamePicking,
                    setUpContactsPicking = callbacks.setUpContactsPicking,
                    onFinish = onFinish,
                    navigate = navigate
                )
            }
    }

    LaunchedEffect(Unit) {
        callbacks.loadAd(activity, activity.getString(R.string.ad_id_interstitial))
        if (activity.areAllPermissionsGranted(permissions)) {
            callbacks.fetchGroups()
        } else {
            launcherMultiplePermissions.launch(permissions.toTypedArray())
        }
    }
}

@Composable
private fun LabelsList(
    screenState: HomeScreenState,
    onRingtoneChosen: (Long, Uri?) -> Unit,
    onSetRingtones: (List<GroupItem>, ContentResolver) -> Unit,
    createGroup: (String?) -> Unit,
    onGroupDeleted: (Long) -> Unit,
    onFinish: () -> Unit,
    navigate: (String) -> Unit,
    setUpGroupNamePicking: (GroupItem) -> Unit,
    setUpContactsPicking: (GroupItem) -> Unit,
) {
    TransparentScaffold(
        topBar = {
            RowWithEndButton(label = stringResource(R.string.home)) { onFinish() }
        },
        content = { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                screenState.groupItems.let {
                    items(count = it.size, key = { i -> it[i].id }) { index ->
                        GroupItemRow(
                            item = it[index],
                            onRingtoneChosen = onRingtoneChosen,
                            onGroupDeleted = onGroupDeleted,
                            setUpGroupNamePicking = setUpGroupNamePicking,
                            setUpContactsPicking = setUpContactsPicking,
                            navigate = navigate
                        )
                    }
                }
            }
        },
        bottomBar = {
            HomeScreenBottomBar(screenState, onSetRingtones) {
                createGroup(it)
            }
        }
    )
}

@Composable
private fun HomeScreenBottomBar(
    screenState: HomeScreenState,
    onSetRingtones: (List<GroupItem>, ContentResolver) -> Unit,
    onCreateGroup: (String?) -> Unit,
) {
    // TODO create launcher for the picker and use onCreateGroup
    val contentResolver = LocalContext.current.contentResolver
    Column {
        Row(
            modifier = Modifier
                .padding(PaddingValues(16.dp))
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundCornerButton(
                modifier = Modifier.weight(1f),
                btnLabel = stringResource(R.string.add_group),
                onClick = { onCreateGroup("NewGroup1") }, //TODO CRETE GROUP NAME
            )
            Spacer(modifier = Modifier.width(16.dp))
            RoundCornerButton(
                modifier = Modifier.weight(1f),
                btnLabel = stringResource(R.string.do_the_magic),
                isEnabled = screenState.groupItems.isEmpty().not(),
                onClick = { onSetRingtones(screenState.groupItems, contentResolver) }
            )
        }

        BannerAdView(
            modifier = Modifier.fillMaxWidth(),
            adId = stringResource(R.string.ad_id_banner)
        )
    }
}