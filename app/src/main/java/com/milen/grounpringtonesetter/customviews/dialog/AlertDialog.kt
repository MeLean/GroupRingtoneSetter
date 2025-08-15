package com.milen.grounpringtonesetter.customviews.dialog

import android.app.Activity
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.milen.grounpringtonesetter.R

internal data class ButtonData(
    @StringRes val textId: Int = R.string.confirm,
    val onClick: () -> Unit = {},
)

internal fun Activity.showAlertDialog(
    @StringRes titleResId: Int,
    message: String,
    cancelButtonData: ButtonData? = ButtonData(R.string.cancel),
    confirmButtonData: ButtonData,
) {
    val dialog = AlertDialog.Builder(this, R.style.AlertDialogCustom)
        .setTitle(titleResId)
        .setMessage(message)
        .setPositiveButton(confirmButtonData.textId) { d, _ ->
            d.dismiss(); confirmButtonData.onClick()
        }
        .apply {
            cancelButtonData?.let {
                setNegativeButton(it.textId) { d, _ -> d.dismiss(); it.onClick() }
            }
        }
        .create()

    dialog.setOnShowListener {
        val minWidthPx = resources.getDimensionPixelSize(R.dimen.dialog_min_width) // e.g., 320dp
        dialog.window?.decorView?.minimumWidth = minWidthPx
    }

    if (!(isFinishing || isDestroyed)) dialog.show()
}