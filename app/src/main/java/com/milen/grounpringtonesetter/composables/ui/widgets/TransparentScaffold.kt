package com.milen.grounpringtonesetter.composables.ui.widgets

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun TransparentScaffold(
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    content: @Composable (paddingValues: PaddingValues) -> Unit,
) = Scaffold(
    backgroundColor = Color.Transparent,
    topBar = topBar,
    content = content,
    bottomBar = bottomBar
)