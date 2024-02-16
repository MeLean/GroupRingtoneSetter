package com.milen.grounpringtonesetter.composables.ui.buttons

import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.milen.grounpringtonesetter.R

@Composable
fun TextColorVectorButton(
    imageVector: ImageVector,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
    ) {
        // Use Icon instead of Image for applying tint
        Icon(
            imageVector = imageVector,
            contentDescription = "Icon",
            modifier = Modifier.size(24.dp),
            tint = colorResource(id = R.color.textColor)
        )
    }
}
