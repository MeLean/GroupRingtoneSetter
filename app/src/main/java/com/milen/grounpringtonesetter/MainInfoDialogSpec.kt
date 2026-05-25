package com.milen.grounpringtonesetter

import androidx.annotation.StringRes
import com.milen.grounpringtonesetter.ui.ScreenInfoProvider

internal data class MainInfoDialogSpec(
    @param:StringRes val screenMessageResId: Int? = null,
    @param:StringRes val secondaryActionTextResId: Int? = null,
) {
    val shouldShowAppInfoDirectly: Boolean
        get() = screenMessageResId == null
}

internal fun resolveMainInfoDialogSpec(
    @StringRes screenMessageResId: Int?,
): MainInfoDialogSpec =
    if (screenMessageResId == null) {
        MainInfoDialogSpec()
    } else {
        MainInfoDialogSpec(
            screenMessageResId = screenMessageResId,
            secondaryActionTextResId = R.string.about_app
        )
    }

internal fun resolveMainInfoDialogSpec(
    screenInfoProvider: ScreenInfoProvider?,
): MainInfoDialogSpec = resolveMainInfoDialogSpec(screenInfoProvider?.getToolbarInfoMessageResId())
