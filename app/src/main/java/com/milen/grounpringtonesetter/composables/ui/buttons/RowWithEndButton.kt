package com.milen.grounpringtonesetter.ui.composables

import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp


@Composable
fun RowWithEndButton(
    modifier: Modifier = Modifier,
    label: String = "",
    imageVector: ImageVector = Icons.Filled.Close,
    onClick: () -> Unit
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        TextWidget(
            text = label,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center)
        )
        Row(
            Modifier.align(Alignment.CenterEnd)
        ) {
            IconButton(
                onClick = { onClick() },
                Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = imageVector,
                    contentDescription = "End button image",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
