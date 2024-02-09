package com.milen.grounpringtonesetter.screens.nointernet

import BackPressHandler
import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.composables.eventobservers.InternetConnectivity
import com.milen.grounpringtonesetter.composables.ui.buttons.RoundCornerButton
import com.milen.grounpringtonesetter.composables.ui.screens.FullscreenImageWithContent
import com.milen.grounpringtonesetter.composables.ui.texts.TextH6Widget
import com.milen.grounpringtonesetter.composables.ui.widgets.TransparentScaffold
import com.milen.grounpringtonesetter.navigation.Destination
import com.milen.grounpringtonesetter.ui.composables.RowWithEndButton

@Composable
fun NoInternetScreen(
    navigate: (String) -> Unit,
) {
    val activity = LocalContext.current as Activity
    val finishActivity = activity::finish

    BackPressHandler { finishActivity() }

    FullscreenImageWithContent(painter = painterResource(id = R.drawable.ringtone_background_3)) {
        TransparentScaffold(
            topBar = {
                RowWithEndButton(label = stringResource(R.string.home)) { finishActivity() }
            },
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(it),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    TextH6Widget(
                        text = stringResource(R.string.no_internet_description),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        maxLines = 4
                    )
                }
            },
            bottomBar = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    RoundCornerButton(
                        modifier = Modifier.padding(vertical = 16.dp, horizontal = 32.dp),
                        btnLabel = stringResource(R.string.open_network_settings)
                    ) {
                        Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }.also { activity.startActivity(it) }
                    }
                }

            }
        )
    }

    InternetConnectivity(
        onConnectionAvailable = { navigate(Destination.HOME.route) }
    )
}