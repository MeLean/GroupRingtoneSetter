package com.milen.grounpringtonesetter.customviews.ui.texts

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.milen.grounpringtonesetter.R

internal class CustomTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = R.style.CustomTextViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    init {
        setTextColor(resources.getColor(R.color.textColor, null))
    }
}
