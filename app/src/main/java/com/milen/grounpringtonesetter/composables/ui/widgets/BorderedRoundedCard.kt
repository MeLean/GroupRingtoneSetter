package com.milen.grounpringtonesetter.composables.ui.widgets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.milen.grounpringtonesetter.R

@Composable
fun BorderedRoundedCard(
    modifier: Modifier,
    content: @Composable () -> Unit
) =
    Card(
        modifier = modifier,
        elevation = 16.dp,
        shape = RoundedCornerShape(16.dp),
        backgroundColor = colorResource(id = R.color.transparent_black),
        border = BorderStroke(2.dp, color = colorResource(id = R.color.textColor)),
        content = content
    )