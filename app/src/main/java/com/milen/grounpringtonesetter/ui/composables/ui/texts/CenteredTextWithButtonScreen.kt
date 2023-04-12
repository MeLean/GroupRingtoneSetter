package com.milen.grounpringtonesetter.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun CenteredTextWithButtonScreen(
    text: String,
    btnLabel: String,
    onClick: () -> Unit,
) {
    Scaffold(
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
            RoundCornerButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                btnLabel = btnLabel
            ) { onClick() }
        }
    )
}