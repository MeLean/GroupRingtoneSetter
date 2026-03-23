package com.milen.grounpringtonesetter.customviews.dialog

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckedTextView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.utils.currentThemeAppearance
import com.milen.grounpringtonesetter.utils.trackSuppressedFailure

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
                } catch (throwable: Throwable) {
                    act.trackSuppressedFailure(
                        "AlertDialog.showDialogSafe.onDestroy.dismiss",
                        throwable
                    )
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
            } catch (throwable: Throwable) {
                trackSuppressedFailure(
                    "AlertDialog.showDialogSafe.onShow.dismiss",
                    throwable
                )
            }
        }
    }

    return try {
        dialog.show()
        dialog.applyHomeDialogTheme(this)
        dialog.window?.let { win -> win.attributes = win.attributes }
        dialog
    } catch (throwable: WindowManager.BadTokenException) {
        trackSuppressedFailure(
            "AlertDialog.showDialogSafe.badToken",
            throwable
        )
        null
    }
}

internal fun AlertDialog.applyHomeDialogTheme(activity: Activity) {
    val themeAppearance = activity.currentThemeAppearance()
    val backgroundColor =
        ContextCompat.getColor(activity, themeAppearance.dialogBackgroundColorRes)
    val borderColor =
        ContextCompat.getColor(activity, themeAppearance.dialogBorderColorRes)
    val textColor = ContextCompat.getColor(activity, themeAppearance.textColorRes)
    val actionTextColor =
        ContextCompat.getColor(activity, themeAppearance.dialogActionTextColorRes)

    window?.setBackgroundDrawable(
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = activity.resources.displayMetrics.density * 16f
            setColor(backgroundColor)
            setStroke((activity.resources.displayMetrics.density).toInt().coerceAtLeast(1), borderColor)
        }
    )

    listOf(
        androidx.appcompat.R.id.parentPanel,
        androidx.appcompat.R.id.topPanel,
        androidx.appcompat.R.id.contentPanel,
        androidx.appcompat.R.id.customPanel,
        androidx.appcompat.R.id.buttonPanel
    ).forEach { viewId ->
        findViewById<View>(viewId)?.setBackgroundColor(Color.TRANSPARENT)
    }

    findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(textColor)
    findViewById<TextView>(android.R.id.message)?.setTextColor(textColor)

    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(actionTextColor)
    getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(actionTextColor)
    getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(actionTextColor)

    listView?.let { list ->
        list.setBackgroundColor(Color.TRANSPARENT)
        list.divider = ColorDrawable(borderColor)
        list.dividerHeight = (activity.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        list.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                child?.applyChoiceListRowTheme(textColor, actionTextColor)
            }

            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
        list.post {
            repeat(list.childCount) { index ->
                list.getChildAt(index)?.applyChoiceListRowTheme(textColor, actionTextColor)
            }
        }
    }
}

private fun View.applyChoiceListRowTheme(
    textColor: Int,
    actionTextColor: Int,
) {
    val checkedTextView = findViewById<CheckedTextView>(android.R.id.text1)
        ?: (this as? CheckedTextView)
    checkedTextView?.apply {
        setTextColor(textColor)
        checkMarkTintList = ColorStateList.valueOf(actionTextColor)
    }
}
