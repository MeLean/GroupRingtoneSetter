package com.milen.grounpringtonesetter.screens.picker

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.composables.ui.ads.BannerAdView
import com.milen.grounpringtonesetter.composables.ui.buttons.RoundCornerButton
import com.milen.grounpringtonesetter.composables.ui.screens.FullScreenLoading
import com.milen.grounpringtonesetter.composables.ui.texts.InputViewWithHint
import com.milen.grounpringtonesetter.composables.ui.texts.TextWidget
import com.milen.grounpringtonesetter.composables.ui.widgets.ContactsListWithSelection
import com.milen.grounpringtonesetter.composables.ui.widgets.TransparentScaffold
import com.milen.grounpringtonesetter.screens.picker.data.PickerData
import com.milen.grounpringtonesetter.screens.picker.data.PickerResultData
import com.milen.grounpringtonesetter.ui.composables.RowWithEndButton

@Composable
internal fun PickerScreen(
    callbacks: PickerViewModelCallbacks,
    onDone: (PickerResultData) -> Unit,
    goBack: () -> Boolean
) {
    val screenState by callbacks.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = true) { onDone(PickerResultData.Canceled) }

    when {
        screenState.isLoading -> FullScreenLoading()
        else -> PickerScreenContent(screenState.pikerData, onDone)
    }
}

@Composable
private fun PickerScreenContent(pickerData: PickerData?, onDone: (PickerResultData) -> Unit) {
    TransparentScaffold(
        topBar = {
            RowWithEndButton(
                label = pickerData?.titleId?.let { stringResource(id = it) }.orEmpty()
            ) {
                onDone(PickerResultData.Canceled)
            }

        },
        bottomBar = {
            Column {
                Row(
                    modifier = Modifier
                        .padding(PaddingValues(16.dp))
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundCornerButton(
                        btnLabel = stringResource(R.string.done),
                        onClick = { onDone(PickerResultData.Canceled) }, // TODO extract result
                    )
                }

                BannerAdView(
                    modifier = Modifier.fillMaxWidth(),
                    adId = stringResource(R.string.ad_id_banner)
                )
            }
        }
    ) {
        Column(
            Modifier
                .padding(8.dp)
                .padding(it)
        ) {
            ContentForPickerData(pickerData = pickerData, onDone = onDone)
        }
    }
}

@Composable
private fun ContentForPickerData(
    pickerData: PickerData?,
    onDone: (PickerResultData) -> Unit
): Unit =
    when (pickerData) {
        is PickerData.GroupNameChange ->
            InputStringPickerContent(pickerData, onDone)

        is PickerData.ContactsPiker ->
            ContactsPickerContent(pickerData, onDone)

        else -> TextWidget(
            text = stringResource(id = R.string.something_went_wrong),
            textAlign = TextAlign.Center
        )
    }

@Composable
private fun ContactsPickerContent(
    contactsPickerData: PickerData.ContactsPiker,
    onDone: (PickerResultData) -> Unit
) {
    val context = LocalContext.current

    Column {
        TextWidget(
            text = stringResource(id = R.string.manage_contacts_group_name_desc),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        ContactsListWithSelection(
            groupId = contactsPickerData.groupItem.id,
            allContacts = contactsPickerData.allContacts,
            currentGroupContacts = contactsPickerData.groupItem.contacts
        ) {
            onDone(it)
        }
    }
}

@Composable
private fun InputStringPickerContent(
    pickerData: PickerData.GroupNameChange,
    onDone: (PickerResultData) -> Unit
) {
    val context = LocalContext.current

    Column(Modifier.padding(top = 8.dp)) {
        TextWidget(
            text = stringResource(id = R.string.edit_group_name_desc),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        InputViewWithHint(
            hint = stringResource(id = R.string.edit_group_name),
            initialValue = pickerData.groupItem.groupName,
            onDone = { (context as? ComponentActivity)?.finish() }
        ) { newGroupName ->
            onDone(
                PickerResultData.GroupNameChanged(
                    groupId = pickerData.groupItem.id,
                    oldName = pickerData.groupItem.groupName,
                    newGroupName = newGroupName
                )
            )
        }
    }
}


