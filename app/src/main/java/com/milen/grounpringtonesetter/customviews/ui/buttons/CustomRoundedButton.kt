package com.milen.grounpringtonesetter.customviews.ui.buttons

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.databinding.CustomRoundedButtonBinding
import com.milen.grounpringtonesetter.utils.currentThemeAppearance

internal class CustomRoundedButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = CustomRoundedButtonBinding.inflate(
        LayoutInflater.from(context), this
    )

    private var lastClickTime = 0L
    private val debounceInterval = 1000L

    init {
        context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.CustomRoundedButton,
            0,
            0
        ).apply {
            try {
                binding.btnMain.text = getString(R.styleable.CustomRoundedButton_buttonLabel)
            } finally {
                recycle()
            }
        }

        val themeAppearance = context.currentThemeAppearance()
        setColors(
            backgroundColor = ContextCompat.getColor(
                context,
                themeAppearance.actionButtonBackgroundColorRes
            ),
            textColor = ContextCompat.getColor(
                context,
                themeAppearance.actionButtonTextColorRes
            )
        )
    }

    fun setColors(
        @ColorInt backgroundColor: Int,
        @ColorInt textColor: Int,
    ) {
        binding.btnMain.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        binding.btnMain.setTextColor(textColor)
    }

    fun setOnClickListener(listener: () -> Unit) {
        binding.btnMain.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastClickTime >= debounceInterval) {
                lastClickTime = now
                listener()
            }
        }
    }
}
