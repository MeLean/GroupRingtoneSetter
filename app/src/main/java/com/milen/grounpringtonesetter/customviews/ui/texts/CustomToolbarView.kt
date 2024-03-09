package com.milen.grounpringtonesetter.customviews.ui.texts

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.milen.grounpringtonesetter.databinding.CustomToolbarViewBinding

class CustomToolbarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var binding: CustomToolbarViewBinding

    init {
        binding = CustomToolbarViewBinding.inflate(LayoutInflater.from(context), this)
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
}