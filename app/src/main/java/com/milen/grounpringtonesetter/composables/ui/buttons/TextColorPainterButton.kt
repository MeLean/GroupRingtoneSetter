package com.milen.grounpringtonesetter.composables.ui.buttons

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.milen.grounpringtonesetter.R

@Composable
fun TextColorPainterButton(
    @DrawableRes resourceId: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Image(
            painter = painterResource(resourceId),
            contentDescription = "Icon Button"
        )
    }
}
