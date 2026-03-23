package com.milen.grounpringtonesetter.customviews.ui.texts

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.utils.currentThemeAppearance

internal class CircleWithText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {

    init {
        background = ContextCompat.getDrawable(context, R.drawable.circle_background)
        textAlignment = TEXT_ALIGNMENT_CENTER
        val themeAppearance = context.currentThemeAppearance()
        setColors(
            backgroundColor = ContextCompat.getColor(
                context,
                themeAppearance.counterBackgroundColorRes
            ),
            textColor = ContextCompat.getColor(
                context,
                themeAppearance.counterTextColorRes
            )
        )
    }

    fun setColors(
        @ColorInt backgroundColor: Int,
        @ColorInt textColor: Int,
    ) {
        backgroundTintList = ColorStateList.valueOf(backgroundColor)
        setTextColor(textColor)
    }
}
