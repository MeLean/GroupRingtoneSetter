package com.milen.grounpringtonesetter.ui.composables

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.composables.ui.ads.BannerAdView

@Composable
fun CenteredTextWithButtonScreen(
    title: String = "",
    text: String,
    btnLabel: String,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { onClose() }) {
                        Icon(Icons.Filled.Close, "Close")
                    }
                },
                backgroundColor = MaterialTheme.colors.surface,
                elevation = 0.dp
            )
        },
        content = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(it),
                contentAlignment = Alignment.Center
            ) {
                TextWidget(
                    text = text,
                    style = MaterialTheme.typography.h4,
                    textAlign = TextAlign.Center
                )
            }
        },
        bottomBar = {
            Column {
                RoundCornerButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    btnLabel = btnLabel
                ) { onClick() }

                BannerAdView(
                    modifier = Modifier.fillMaxWidth(),
                    adId = stringResource(R.string.ad_id_banner)
                )
            }
        }
    )
}