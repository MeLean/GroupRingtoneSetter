package com.milen.grounpringtonesetter.composables.ui.texts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.composables.ui.ads.BannerAdView
import com.milen.grounpringtonesetter.composables.ui.buttons.RoundCornerButton
import com.milen.grounpringtonesetter.composables.ui.widgets.TransparentScaffold
import com.milen.grounpringtonesetter.composables.ui.widgets.TransparentToolbar

@Composable
fun CenteredTextWithButtonScreen(
    title: String = "",
    text: String,
    btnLabel: String,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    TransparentScaffold(
        topBar = {
            TransparentToolbar(title = title, onClose = onClose)
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