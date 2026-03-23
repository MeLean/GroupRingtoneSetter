package com.milen.grounpringtonesetter.utils

import android.content.Context
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.ui.home.HomeThemeAppearance
import com.milen.grounpringtonesetter.ui.home.HomeThemeOption
import com.milen.grounpringtonesetter.ui.home.toAppearance

internal fun Context.currentThemeAppearance(): HomeThemeAppearance =
    (applicationContext as? App)?.currentThemeAppearance()
        ?: HomeThemeOption.CLASSIC.toAppearance()
