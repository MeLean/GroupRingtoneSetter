package com.milen.grounpringtonesetter.customviews.ui.buttons


import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.databinding.CustomTextColorButtonBinding

class CustomTextColorImageButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
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
    }

    override fun setOnClickListener(listener: OnClickListener?) {
        binding.imageButton.setOnClickListener(listener)
    }
}