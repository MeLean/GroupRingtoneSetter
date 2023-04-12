package com.milen.grounpringtonesetter.screens.home

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.composables.eventobservers.InternetConnectivity
import com.milen.grounpringtonesetter.composables.ui.ads.ShowAd
import com.milen.grounpringtonesetter.data.GroupItem
import com.milen.grounpringtonesetter.navigation.Destination
import com.milen.grounpringtonesetter.ui.composables.*
import com.milen.grounpringtonesetter.utils.areAllPermissionsGranted
import com.milen.grounpringtonesetter.utils.getFileNameOrEmpty


@Composable
fun HomeScreen(
    callbacks: HomeViewModelCallbacks,
    navigate: (String) -> Unit,
    onFinish: () -> Unit
) {
    val screenState by callbacks.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity
    val permissions = listOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_CONTACTS
    )

    val launcherMultiplePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        when {
            activity.areAllPermissionsGranted(permissions) -> callbacks.fetchLabels(activity.contentResolver)
            else -> {
                callbacks.hideLoading()
            }
        }
    }

    InternetConnectivity(
        onConnectionLost = { navigate(Destination.NO_INTERNET.route) }
    )

    when {
        screenState.isLoading -> FullScreenLoading()
        screenState.groupItems.isEmpty() ->
            CenteredTextWithButtonScreen(
                text = stringResource(R.string.groups_not_found),
                btnLabel = stringResource(R.string.close_app),
                onClick = onFinish
            )
        screenState.isAllDone ->
            CenteredTextWithButtonScreen(
                text = stringResource(R.string.everything_set),
                btnLabel = stringResource(R.string.close_app),
                onClick = onFinish
            )
        else -> LabelsList(
            screenState,
            callbacks.onRingtoneChosen,
            callbacks.onSetRingtones,
            callbacks.fetchLabels,
            onFinish
        )
    }

    if (screenState.shouldShowAd) {
        ShowAd(
            onDone = {
                callbacks.onSetRingtones(
                    screenState.groupItems,
                    activity.contentResolver,
                    false
                )
            },
            onAdLoaded = {
                callbacks.hideLoading()
            }
        )
    }

    LaunchedEffect(Unit) {
        if (activity.areAllPermissionsGranted(permissions)) {
            callbacks.fetchLabels(activity.contentResolver)
        } else {
            launcherMultiplePermissions.launch(permissions.toTypedArray())
        }
    }
}

@Composable
private fun LabelsList(
    screenState: HomeScreenState,
    onRingtoneChosen: (String, Uri?) -> Unit,
    onSetRingtones: (MutableList<GroupItem>, ContentResolver, Boolean) -> Unit,
    fetchLabels: (ContentResolver) -> Unit,
    onFinish: () -> Unit
) {
    Scaffold(
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
                    items(
                        count = it.size,
                        key = { i -> it[i].groupName }
                    )
                    { index ->
                        LabelListItem(it[index], onRingtoneChosen)
                    }
                }
            }
        },
        bottomBar = {
            HomeScreenBottomBar(fetchLabels, screenState, onSetRingtones)
        }
    )
}

@Composable
private fun HomeScreenBottomBar(
    fetchLabels: (ContentResolver) -> Unit,
    screenState: HomeScreenState,
    onSetRingtones: (MutableList<GroupItem>, ContentResolver, Boolean) -> Unit
) {
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
                btnLabel = stringResource(R.string.refresh_groups),
                onClick = { fetchLabels(contentResolver) },
            )
            Spacer(modifier = Modifier.width(16.dp))
            RoundCornerButton(
                modifier = Modifier.weight(1f),
                btnLabel = stringResource(R.string.do_the_magic),
                isEnabled = screenState.groupItems.isEmpty().not(),
                onClick = { onSetRingtones(screenState.groupItems, contentResolver, true) }
            )
        }

        BannerAdView(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun LabelListItem(
    item: GroupItem,
    onRingtoneChosen: (String, Uri?) -> Unit
) {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            result.data?.data?.let {
                onRingtoneChosen(item.groupName, it)
            }
        }
    )

    val (isDialogOpen, setIsDialogOpen) = remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 16.dp),
        elevation = 8.dp,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
                content = {
                    TextWidget(
                        text = item.groupName,
                        style = MaterialTheme.typography.h6,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    TextWidget(
                        text = item.ringtoneUri.getFileNameOrEmpty(context),
                        style = MaterialTheme.typography.body2,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    CircleWithText(
                        text = "${item.contacts.size}",
                        onClick = {
                            if (item.contacts.isNotEmpty()) {
                                setIsDialogOpen(true)
                            }
                        }
                    )
                }
            )
            RoundCornerButton(
                btnLabel = stringResource(R.string.choose_ringtone),
                onClick = {
                    Intent(Intent.ACTION_PICK, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = MediaStore.Audio.Media.CONTENT_TYPE
                    }.also {
                        launcher.launch(it)
                    }
                }
            )
        }
    }

    if (isDialogOpen) {
        val contacts = item.contacts
        AlertDialog(
            modifier = Modifier.padding(8.dp),
            onDismissRequest = { setIsDialogOpen(false) },
            title = {
                TextWidget(
                    modifier = Modifier.padding(8.dp),
                    text = stringResource(R.string.contacts),
                    style = MaterialTheme.typography.h6,
                )
            },
            text = {
                LazyColumn(Modifier.wrapContentHeight()) {
                    items(
                        count = contacts.size,
                        key = { i -> contacts[i].name }
                    ) { index ->
                        with(contacts[index]) {
                            TextWidget(
                                text = name,
                                style = MaterialTheme.typography.subtitle2,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { setIsDialogOpen(false) }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}