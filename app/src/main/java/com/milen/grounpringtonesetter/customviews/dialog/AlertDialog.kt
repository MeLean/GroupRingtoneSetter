package com.milen.grounpringtonesetter.customviews.dialog

import android.app.Activity
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
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
    isCancelable: Boolean = true,
    isCancelableOnTouchOutside: Boolean = true,
) {
    showDialogSafe(
        configureDialog = { dialog ->
            dialog.setCancelable(isCancelable)
            dialog.setCanceledOnTouchOutside(isCancelableOnTouchOutside)
        }
    ) {
        setTitle(titleResId)
        setMessage(message)
        setPositiveButton(confirmButtonData.textId) { d, _ ->
            try {
                confirmButtonData.onClick()
            } finally {
                d.dismiss()
            }
        }
        cancelButtonData?.let { data ->
            setNegativeButton(data.textId) { d, _ ->
                try {
                    data.onClick()
                } finally {
                    d.dismiss()
                }
            }
        }
    }
}

internal fun Activity.showCustomViewAlertDialog(
    @StringRes titleResId: Int,
    customView: View,
    cancelButtonData: ButtonData? = null,
    confirmButtonData: ButtonData,
) {
    showDialogSafe {
        setTitle(titleResId)
        setView(customView)
        setPositiveButton(confirmButtonData.textId) { d, _ ->
            try {
                confirmButtonData.onClick()
            } finally {
                d.dismiss()
            }
        }
        cancelButtonData?.let { data ->
            setNegativeButton(data.textId) { d, _ ->
                try {
                    data.onClick()
                } finally {
                    d.dismiss()
                }
            }
        }
    }
}

internal fun Activity.showRequiredSingleChoiceDialog(
    @StringRes titleResId: Int,
    message: String,
    options: List<String>,
    onSelected: (Int) -> Unit,
): AlertDialog? {
    if (options.isEmpty()) return null
    var selectedIndex = 0

    return showDialogSafe(
        configureDialog = { dialog ->
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
        }
    ) {
        setTitle(titleResId)
        if (message.isNotBlank()) {
            setMessage(message)
        }
        setSingleChoiceItems(options.toTypedArray(), selectedIndex) { _, which ->
            selectedIndex = which
        }
        setPositiveButton(R.string.confirm) { d, _ ->
            try {
                onSelected(selectedIndex)
            } finally {
                d.dismiss()
            }
        }
    }
}

/** Shared, lifecycle-safe dialog runner to avoid BadTokenException + window leaks. */
private fun Activity.showDialogSafe(
    configureDialog: (AlertDialog) -> Unit = {},
    build: AlertDialog.Builder.() -> Unit,
): AlertDialog? {
    if (isFinishing || isDestroyed) return null

    val dialog = AlertDialog.Builder(this, R.style.AlertDialogCustom)
        .apply(build)
        .create()
    configureDialog(dialog)

    if (isFinishing || isDestroyed) return null

    if (this is ComponentActivity) {
        val act = this
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                try {
                    if (dialog.isShowing) dialog.dismiss()
                } catch (_: Throwable) {
                }
                act.lifecycle.removeObserver(this)
            }
        }
        act.lifecycle.addObserver(observer)
        dialog.setOnDismissListener { act.lifecycle.removeObserver(observer) }
    }

    dialog.setOnShowListener {
        if (isFinishing || isDestroyed) {
            try {
                dialog.dismiss()
            } catch (_: Throwable) {
            }
        }
    }

    return try {
        dialog.show()
        dialog.window?.let { win -> win.attributes = win.attributes }
        dialog
    } catch (_: WindowManager.BadTokenException) {
        null
    }
}
