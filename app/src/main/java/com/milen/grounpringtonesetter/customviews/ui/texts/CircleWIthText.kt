package com.milen.grounpringtonesetter.customviews.ui.texts

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.milen.grounpringtonesetter.R

internal class CircleWithText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {

    init {
        background = ContextCompat.getDrawable(context, R.drawable.circle_background)
        textAlignment = TEXT_ALIGNMENT_CENTER
        setTextColor(ContextCompat.getColor(context, R.color.textColor))
    }
}