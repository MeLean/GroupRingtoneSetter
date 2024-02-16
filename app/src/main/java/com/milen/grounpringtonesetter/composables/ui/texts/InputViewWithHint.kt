@file:OptIn(ExperimentalMaterialApi::class)

package com.milen.grounpringtonesetter.composables.ui.texts

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.milen.grounpringtonesetter.R

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun InputViewWithHint(
    hint: String,
    initialValue: String,
    onDone: () -> Unit = {},
    onTextChange: (String) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val textColor = colorResource(id = R.color.textColor)
    val textState = remember { mutableStateOf(initialValue) }

    OutlinedTextField(
        value = textState.value,
        onValueChange = { newText ->
            textState.value = newText
            onTextChange(newText)
        },
        label = { Text(text = hint, style = TextStyle(color = textColor)) },
        singleLine = true,
        textStyle = TextStyle(color = textColor),
        keyboardActions = KeyboardActions(
            onDone = {
                keyboardController?.hide()
                onDone()
            }
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done
        ),
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.outlinedTextFieldColors(
            focusedBorderColor = textColor,
            unfocusedBorderColor = textColor,
            textColor = textColor,
            focusedLabelColor = textColor,
            unfocusedLabelColor = textColor
        )
    )
}