package com.milen.grounpringtonesetter.composables.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.milen.grounpringtonesetter.R

@Composable
fun FullScreenLoading() =
    FullscreenImageWithContent(painterResource(id = R.drawable.ringtone_background_3)) {
        CircularProgressIndicator(
            modifier = Modifier.size(52.dp)
        )
    }





