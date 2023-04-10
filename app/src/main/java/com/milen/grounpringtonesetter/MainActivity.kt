package com.milen.grounpringtonesetter


import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.milen.grounpringtonesetter.data.GroupItem
import com.milen.grounpringtonesetter.ui.composables.*
import com.milen.grounpringtonesetter.ui.screenstate.LabelListScreenState
import com.milen.grounpringtonesetter.ui.theme.LabelRingtoneSetterTheme
import com.milen.grounpringtonesetter.utils.getFileNameOrEmpty
import com.milen.grounpringtonesetter.utils.loadRewardAd
import com.milen.grounpringtonesetter.viewmodel.ListViewModel
import kotlinx.coroutines.flow.StateFlow


class MainActivity : ComponentActivity() {
    private val viewModel: ListViewModel by viewModels()
    private var rewardedInterstitialAd: RewardedInterstitialAd? = null
    private var loadAddErrors: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            MobileAds.initialize(this) { loadAd() }
            LabelRingtoneSetterTheme {
                LabelsListScreen(
                    uiState = viewModel.uiState,
                    onSetRingtones = ::setRingTones,
                    onRingtoneChosen = ::onRingtoneChosen,
                    fetchLabels = ::fetchLabels,
                    onPermissionDenied = ::onPermissionDenied,
                    onFinish = ::finish
                )
            }
        }
    }

    override fun onDestroy() {
        rewardedInterstitialAd = null
        super.onDestroy()
    }

    private fun loadAd() {
        loadRewardAd(adId = getString(R.string.ad_id_reward)) { ad ->
            ad?.let { rewardedInterstitialAd = it } ?: run {
                loadAddErrors++
                if (loadAddErrors <= 3) {
                    loadAd()
                }
            }
        }
    }

    private fun onPermissionDenied() {
        Toast.makeText(this, getString(R.string.need_permission_to_run), Toast.LENGTH_LONG).show()
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }.also {
            startActivity(it)
        }
    }

    private fun fetchLabels(): Unit =
        viewModel.fetchLabels(contentResolver)

    private fun onRingtoneChosen(label: String, uri: Uri?): Unit =
        viewModel.onRingtoneChosen(label, uri)

    private fun setRingTones(items: MutableList<GroupItem>): Unit =
        rewardedInterstitialAd?.let {
            it.show(this) { setRingtone(items) }
        } ?: setRingtone(items)

    private fun setRingtone(items: MutableList<GroupItem>): Unit =
        viewModel.setRingTones(items, contentResolver)
}

@Composable
private fun LabelsListScreen(
    uiState: StateFlow<LabelListScreenState>,
    fetchLabels: () -> Unit,
    onSetRingtones: (MutableList<GroupItem>) -> Unit,
    onRingtoneChosen: (String, Uri?) -> Unit,
    onPermissionDenied: () -> Unit,
    onFinish: () -> Unit
) {
    val screenState by uiState.collectAsStateWithLifecycle()

    val launcherMultiplePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        if (permissionsMap.values.reduce { acc, next -> acc && next }) {
            fetchLabels()
        } else {
            onPermissionDenied()
        }
    }

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
            onRingtoneChosen,
            onSetRingtones,
            fetchLabels
        )
    }

    if (screenState.areLabelsFetched.not()) {
        LaunchedEffect(stringResource(R.string.permission_required)) {
            launcherMultiplePermissions.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_CONTACTS,
                    Manifest.permission.READ_CONTACTS
                )
            )
        }
    }
}

@Composable
private fun LabelsList(
    screenState: LabelListScreenState,
    onRingtoneChosen: (String, Uri?) -> Unit,
    onSetRingtones: (MutableList<GroupItem>) -> Unit,
    fetchLabels: () -> Unit
) {
    Scaffold(
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
            LabelScreenBottomBar(fetchLabels, screenState, onSetRingtones)
        }
    )
}

@Composable
private fun LabelScreenBottomBar(
    fetchLabels: () -> Unit,
    screenState: LabelListScreenState,
    onSetRingtones: (MutableList<GroupItem>) -> Unit
) {
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
                onClick = { fetchLabels() },

                )
            Spacer(modifier = Modifier.width(16.dp))
            RoundCornerButton(
                modifier = Modifier.weight(1f),
                btnLabel = stringResource(R.string.do_the_magic),
                isEnabled = screenState.groupItems.isEmpty().not(),
                onClick = { onSetRingtones(screenState.groupItems) }

            )
        }

        AdView(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun AdView(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = context.getString(R.string.ad_id_banner)
                loadAd(AdRequest.Builder().build())
            }
        }
    )
}

@Composable
private fun LabelListItem(item: GroupItem, onRingtoneChosen: (String, Uri?) -> Unit) {
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
                    Intent(Intent.ACTION_PICK).apply {
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