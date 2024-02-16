package com.milen.grounpringtonesetter.composables.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.milen.grounpringtonesetter.R

@Composable
fun FullScreenLoading() =
    FullscreenImageWithCenteredContent {
        CircularProgressIndicator(
            modifier = Modifier.size(52.dp),
            color = colorResource(id = R.color.textColor)
        )
    }





