package com.milen.grounpringtonesetter.customviews.ui.texts

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import com.milen.grounpringtonesetter.databinding.CustomToolbarViewBinding

internal class CustomToolbarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private var binding: CustomToolbarViewBinding =
        CustomToolbarViewBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
    }

    fun setTitle(title: String) {
        binding.ctvTitle.text = title
    }


    fun setActionClick(callback: () -> Unit) {
        binding.btnCustomAction.setOnClickListener { callback() }
    }

    fun setInfoData(callback: () -> Unit) {
        binding.btnInfoAction.apply {
            isVisible = true
            setOnClickListener { callback() }
        }
    }

    fun applyColors(
        @ColorInt backgroundColor: Int,
        @ColorInt textColor: Int,
        @ColorInt iconTintColor: Int,
    ) {
        setBackgroundColor(backgroundColor)
        binding.ctvTitle.setTextColor(textColor)
        binding.btnInfoAction.imageTintList = ColorStateList.valueOf(iconTintColor)
        binding.btnCustomAction.imageTintList = ColorStateList.valueOf(iconTintColor)
    }
}
