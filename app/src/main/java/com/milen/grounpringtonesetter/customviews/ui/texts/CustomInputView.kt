package com.milen.grounpringtonesetter.customviews.ui.texts

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.google.android.material.textfield.TextInputEditText
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.databinding.CustomInputViewBinding
import com.milen.grounpringtonesetter.utils.hideSoftInput


class CustomInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.textInputStyle
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding: CustomInputViewBinding =
        CustomInputViewBinding.inflate(LayoutInflater.from(context), this)

    private val editText: TextInputEditText
        get() = binding.editTextInput

    init {
        context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.CustomInputView,
            0, 0
        ).apply {
            try {
                binding.textInputLayout.hint = getString(R.styleable.CustomInputView_android_hint)
                binding.editTextInput.setText(getString(R.styleable.CustomInputView_initialText))
            } finally {
                recycle()
            }
        }

        binding.editTextInput.apply {
            setOnEditorActionListener { view, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    view.clearFocus()
                }

                false
            }
        }


    }

    fun getText(): String = editText.text.toString()

    fun setText(text: String) {
        editText.setText(text)
    }

    fun setCustomHint(hintText: String) {
        binding.textInputLayout.hint = hintText
    }

    fun setSoftDoneCLicked(callback: () -> Unit) {
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                callback()
                hideSoftInput()
                true
            } else false
        }
    }
}

