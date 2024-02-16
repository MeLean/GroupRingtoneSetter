package com.milen.grounpringtonesetter.composables.dialog

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.composables.ui.texts.TextWidget

@Composable
fun CustomStringListDialog(
    @StringRes titleId: Int? = null,
    itemList: List<String>,
    onDismissRequest: () -> Unit,
    confirmData: ButtonData,
    dismissData: ButtonData? = null,
) {
    AlertDialog(
        modifier = Modifier
            .padding(8.dp),
        backgroundColor = Color.Black,
        title = {
            titleId?.let {
                TextWidget(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    text = stringResource(it),
                    textAlign = TextAlign.Center
                )
            }
        },
        text = {
            LazyColumn(Modifier.wrapContentHeight()) {
                items(
                    count = itemList.size,
                    key = { i -> itemList[i] }
                ) { index ->
                    with(itemList[index]) {
                        TextWidget(
                            text = this,
                            style = MaterialTheme.typography.subtitle2,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = confirmData.run {
            {
                TextButton(
                    onClick = {
                        onDismissRequest()
                        onClick()
                    },
                ) {
                    Text(
                        stringResource(textId),
                        color = colorResource(id = R.color.purple_200)
                    )
                }
            }
        },
        dismissButton = dismissData?.let {
            {
                TextButton(
                    onClick = {
                        onDismissRequest()
                        it.onClick()
                    },
                ) {
                    Text(
                        stringResource(it.textId),
                        color = colorResource(id = R.color.purple_200)
                    )
                }
            }
        }
    )
}