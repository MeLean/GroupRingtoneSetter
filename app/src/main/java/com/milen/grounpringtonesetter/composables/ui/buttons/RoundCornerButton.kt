package com.milen.grounpringtonesetter.composables.ui.buttons

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun RoundCornerButton(
    modifier: Modifier = Modifier,
    btnLabel: String,
    isEnabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .wrapContentHeight(align = Alignment.CenterVertically)
            .padding(4.dp),
        shape = RoundedCornerShape(16.dp),
        enabled = isEnabled
    ) {
        Text(
            text = btnLabel,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(4.dp)
        )
    }
}