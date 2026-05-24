package com.milen.grounpringtonesetter.ui

import androidx.annotation.StringRes

internal interface ScreenInfoProvider {
    @StringRes
    fun getScreenInfoMessageResId(): Int
}
