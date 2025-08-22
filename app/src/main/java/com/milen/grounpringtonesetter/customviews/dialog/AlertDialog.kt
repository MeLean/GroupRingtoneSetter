package com.milen.grounpringtonesetter.customviews.dialog

import android.app.Activity
import android.view.View
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
    cancelButtonData: ButtonData? = null,
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


internal fun Activity.showCustomViewAlertDialog(
    @StringRes titleResId: Int,
    customView: View,
    cancelButtonData: ButtonData? = null,
    confirmButtonData: ButtonData,
) {
    val dialog = AlertDialog.Builder(this, R.style.AlertDialogCustom)
        .setTitle(titleResId)
        .setView(customView)
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