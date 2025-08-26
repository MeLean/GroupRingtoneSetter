package com.milen.grounpringtonesetter.utils

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

private data class InitialPadding(val left: Int, val top: Int, val right: Int, val bottom: Int)

private fun View.recordInitialPadding() =
    InitialPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)

private fun View.requestApplyInsetsWhenAttached() {
    if (isAttachedToWindow) requestApplyInsets()
    else addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.removeOnAttachStateChangeListener(this);v.requestApplyInsets()
        }

        override fun onViewDetachedFromWindow(v: View) {}
    })
}

/** Adds status bar height to the view's top padding. */
fun View.applyStatusBarPadding() {
    val initial = recordInitialPadding()
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        v.updatePadding(top = initial.top + top)
        insets
    }
    requestApplyInsetsWhenAttached()
}

/** Adds max(navigation bar, IME) to the view's bottom padding. Great for roots. */
fun View.applyNavAndImePadding() {
    val initial = recordInitialPadding()
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        v.updatePadding(bottom = initial.bottom + maxOf(b, ime))
        insets
    }
    requestApplyInsetsWhenAttached()
}

/** Use this if you prefer lifting a specific bottom view (e.g., AdBanner) by margin instead of padding root. */
fun View.applyNavAndImeMargin() {
    val lp = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    val initialBottom = lp.bottomMargin
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        (v.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin =
            initialBottom + maxOf(b, ime)
        v.requestLayout()
        insets
    }
    requestApplyInsetsWhenAttached()
}