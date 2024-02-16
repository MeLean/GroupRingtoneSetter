package com.milen.grounpringtonesetter.composables.ui.widgets

import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.milen.grounpringtonesetter.R

@Composable
fun TransparentToolbar(title: String, onClose: () -> Unit) {
    TopAppBar(
        backgroundColor = Color.Transparent,
        title = { Text(title, color = colorResource(id = R.color.textColor)) },
        navigationIcon = {
            IconButton(onClick = { onClose() }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    tint = colorResource(id = R.color.textColor),
                    contentDescription = "Close"
                )
            }
        },
        elevation = 0.dp
    )
}