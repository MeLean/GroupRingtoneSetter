package com.milen.grounpringtonesetter.customviews.ui.buttons


import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.databinding.CustomTextColorButtonBinding
import com.milen.grounpringtonesetter.utils.currentThemeAppearance

internal class CustomTextColorImageButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var binding = CustomTextColorButtonBinding.inflate(LayoutInflater.from(context), this)

    init {
        attrs?.let {
            context.obtainStyledAttributes(it, R.styleable.CustomTextColorImageButton, 0, 0).apply {
                try {
                    binding.imageButton.setImageResource(
                        getResourceId(
                            R.styleable.CustomTextColorImageButton_iconDrawable,
                            -1
                        )
                    )
                } finally {
                    recycle()
                }
            }
        }

        setIconTint(
            ContextCompat.getColor(
                context,
                context.currentThemeAppearance().iconTintColorRes
            )
        )
    }

    override fun setOnClickListener(listener: OnClickListener?) {
        binding.imageButton.setOnClickListener(listener)
    }

    fun setIcon(@DrawableRes drawableRes: Int) {
        binding.imageButton.setImageResource(drawableRes)
    }

    fun setIconTint(@ColorInt color: Int) {
        binding.imageButton.imageTintList = ColorStateList.valueOf(color)
    }
}
