package com.milen.grounpringtonesetter.customviews.ui.texts

import android.widget.EditText
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.SearchView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.milen.grounpringtonesetter.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TextInputLengthTest {

    private val context by lazy {
        ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.Theme_GroupRingtoneSetter,
        )
    }

    @Test
    fun groupNameInputCapsPastedText() {
        val length = AtomicInteger()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val input = CustomInputView(context)
            val editText = input.findViewById<EditText>(R.id.editTextInput)
            editText.setText("a".repeat(2_000))
            length.set(input.getText().length)
        }
        assertEquals(256, length.get())
    }

    @Test
    fun contactSearchCapsPastedText() {
        val length = AtomicInteger()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val search = SearchContactView(context)
            val searchView = search.findViewById<SearchView>(R.id.searchView)
            searchView.setQuery("a".repeat(2_000), false)
            length.set(searchView.query.length)
        }
        assertEquals(256, length.get())
    }
}
