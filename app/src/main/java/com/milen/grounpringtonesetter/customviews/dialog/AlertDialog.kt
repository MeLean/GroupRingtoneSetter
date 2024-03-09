package com.milen.grounpringtonesetter.customviews.dialog

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.milen.grounpringtonesetter.R

data class ButtonData(
    @StringRes val textId: Int = R.string.confirm,
    val onClick: () -> Unit = {}
)

fun Context.showAlertDialog(
    @StringRes titleResId: Int,
    message: String,
    cancelButtonData: ButtonData? = ButtonData(R.string.cancel),
    confirmButtonData: ButtonData
) {
    AlertDialog.Builder(this, R.style.AlertDialogCustom).apply {
        setTitle(titleResId)
        setMessage(message)

        setPositiveButton(confirmButtonData.textId) { dialog, _ ->
            dialog.dismiss()
            confirmButtonData.onClick()
        }

        cancelButtonData?.let {
            setNegativeButton(it.textId) { dialog, _ ->
                dialog.dismiss()
                it.onClick()
            }
        }

        create().show()
    }
}