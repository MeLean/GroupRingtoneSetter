package com.milen.grounpringtonesetter.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.composables.ui.texts.TextH6Widget


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
        TextH6Widget(
            text = label,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
            maxLines = 2
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
                    tint = colorResource(id = R.color.textColor),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
