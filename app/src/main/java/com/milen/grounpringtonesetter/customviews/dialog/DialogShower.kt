package com.milen.grounpringtonesetter.customviews.dialog

import android.app.Activity
import androidx.annotation.StringRes
import com.milen.grounpringtonesetter.R

internal class DialogShower(private val activity: Activity) {
    fun showError(message: String?) {
        activity.showAlertDialog(
            titleResId = R.string.error,
            message = message ?: activity.getString(R.string.something_went_wrong),
            cancelButtonData = null,
            confirmButtonData = ButtonData(R.string.ok)
        )
    }

    fun showErrorById(@StringRes messageId: Int): Unit =
        showError(activity.getString(messageId))

    fun showInfo(@StringRes resId: Int = R.string.info_text, additionalText: String? = null) {
        activity.showAlertDialog(
            titleResId = R.string.info,
            message = "${activity.getString(resId)}${additionalText?.let { "\n\n$it" }.orEmpty()}",
            cancelButtonData = null,
            confirmButtonData = ButtonData(R.string.ok)
        )
    }
}