package com.milen.grounpringtonesetter.composables.dialog

import androidx.annotation.StringRes

data class ButtonData(
    @StringRes val textId: Int,
    val onClick: () -> Unit
)