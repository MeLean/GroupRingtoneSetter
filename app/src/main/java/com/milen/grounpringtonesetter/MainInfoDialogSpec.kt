package com.milen.grounpringtonesetter

import androidx.annotation.StringRes

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
