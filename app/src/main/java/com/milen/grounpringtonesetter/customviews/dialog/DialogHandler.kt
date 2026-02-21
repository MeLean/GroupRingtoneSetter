package com.milen.grounpringtonesetter.customviews.dialog

import android.app.Activity
import androidx.annotation.StringRes
import com.milen.grounpringtonesetter.R

internal class DialogHandler(private val activity: Activity) {
    fun showError(
        message: String?,
        onConfirm: () -> Unit = {},
        isCancelableOnTouchOutside: Boolean = true,
    ) {
        activity.showAlertDialog(
            titleResId = R.string.error,
            message = message ?: activity.getString(R.string.something_went_wrong),
            cancelButtonData = null,
            confirmButtonData = ButtonData(
                textId = R.string.ok,
                onClick = onConfirm
            ),
            isCancelableOnTouchOutside = isCancelableOnTouchOutside
        )
    }

    fun showErrorById(@StringRes messageId: Int) {
        val onConfirm: () -> Unit = if (messageId == R.string.need_permission_to_run) {
            { activity.finishAffinity() }
        } else {
            {}
        }
        val isCancelableOnTouchOutside = messageId != R.string.need_permission_to_run
        showError(
            message = activity.getString(messageId),
            onConfirm = onConfirm,
            isCancelableOnTouchOutside = isCancelableOnTouchOutside
        )
    }

    fun showInfo(@StringRes resId: Int = R.string.info_text) {
        activity.showAlertDialog(
            titleResId = R.string.info,
            message = activity.getString(resId),
            cancelButtonData = null,
            confirmButtonData = ButtonData(R.string.ok)
        )
    }
}
