package com.milen.grounpringtonesetter.customviews.dialog

import android.app.Activity
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.milen.grounpringtonesetter.R

internal data class ButtonData(
    @param:StringRes val textId: Int = R.string.confirm,
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

    dialog.show()
    val lp = dialog.window?.attributes
    lp?.let {
        dialog.window?.attributes = it
    }
    if (isFinishing || isDestroyed) return
}