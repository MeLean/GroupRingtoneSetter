package com.milen.grounpringtonesetter.customviews.ui.buttons

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.databinding.CustomRoundedButtonBinding

class CustomRoundedButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = CustomRoundedButtonBinding.inflate(
        LayoutInflater.from(context), this
    )

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
    }

    fun setOnClickListener(listener: () -> Unit) {
        binding.btnMain.setOnClickListener { listener() }
    }
}