package com.milen.grounpringtonesetter.composables.ui.texts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.milen.grounpringtonesetter.R

@Composable
fun CircleWithText(
    text: String = "",
    backgroundColor: Color = colorResource(R.color.purple_200),
    textColor: Color = colorResource(R.color.white),
    onClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .size(60.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
        content = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(backgroundColor),
                contentAlignment = Alignment.Center,
                content = {
                    Text(
                        text = text,
                        color = textColor,
                        textAlign = TextAlign.Center
                    )
                }
            )
        }
    )
}