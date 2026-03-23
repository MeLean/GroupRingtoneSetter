package com.milen.grounpringtonesetter.customviews.ui.texts

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.utils.currentThemeAppearance

internal class CustomTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.style.CustomTextViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    init {
        val themeAppearance = context.currentThemeAppearance()
        setTextColor(
            ContextCompat.getColor(
                context,
                themeAppearance.textColorRes
            )
        )
    }
}
