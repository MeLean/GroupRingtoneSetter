package com.milen.grounpringtonesetter.composables.ui.widgets

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.composables.dialog.ButtonData
import com.milen.grounpringtonesetter.composables.dialog.CustomStringListDialog
import com.milen.grounpringtonesetter.composables.ui.buttons.RoundCornerButton
import com.milen.grounpringtonesetter.composables.ui.buttons.TextColorPainterButton
import com.milen.grounpringtonesetter.composables.ui.buttons.TextColorVectorButton
import com.milen.grounpringtonesetter.composables.ui.texts.CircleWithText
import com.milen.grounpringtonesetter.composables.ui.texts.TextWidget
import com.milen.grounpringtonesetter.data.GroupItem
import com.milen.grounpringtonesetter.navigation.Destination
import com.milen.grounpringtonesetter.utils.getFileNameOrEmpty

@Composable
fun GroupItemRow(
    item: GroupItem,
    onRingtoneChosen: (Long, Uri?) -> Unit,
    onGroupDeleted: (Long) -> Unit,
    setUpGroupNamePicking: (GroupItem) -> Unit,
    setUpContactsPicking: (GroupItem) -> Unit,
    navigate: (String) -> Unit

) {
    val context = LocalContext.current

    val (dialogType, setActionType) = remember { mutableStateOf(ActionType.NONE) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                onRingtoneChosen(item.id, it)
            }
        }
    )

    BorderedRoundedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 16.dp),
    ) {
        val centerVerticalModifier = Alignment.CenterVertically

        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = centerVerticalModifier) {
                TextWidget(
                    text = item.groupName,
                    style = MaterialTheme.typography.h6,
                    maxLines = 2,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )

                TextColorPainterButton(resourceId = R.drawable.manage_contacts) {
                    setActionType(ActionType.MANAGE_CONTACTS)
                }

                TextColorVectorButton(imageVector = Icons.Filled.Edit) {
                    setActionType(ActionType.EDIT)
                }

                TextColorVectorButton(imageVector = Icons.Filled.Delete) {
                    setActionType(ActionType.DELETE)
                }
            }

            Row(verticalAlignment = centerVerticalModifier) {
                CircleWithText(
                    text = "${item.contacts.size}",
                    onClick = {
                        if (item.contacts.isNotEmpty()) {
                            setActionType(ActionType.CONTACTS)
                        }
                    }
                )
                TextWidget(
                    text = item.ringtoneUri.getFileNameOrEmpty(context),
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    maxLines = 3
                )
                RoundCornerButton(
                    btnLabel = stringResource(R.string.choose_ringtone),
                    onClick = {
                        launcher.launch("audio/*")
                    }
                )
            }
        }
    }

    when (dialogType) {
        ActionType.CONTACTS ->
            CustomStringListDialog(
                titleId = R.string.contacts,
                itemList = item.contacts.map { it.name },
                onDismissRequest = { setActionType(ActionType.NONE) },
                confirmData = ButtonData(textId = R.string.ok) { }
            )

        ActionType.EDIT -> {
            setUpGroupNamePicking(item)
            navigate(Destination.PICKER.route)
        }

        ActionType.DELETE -> {
            CustomStringListDialog(
                titleId = R.string.delete_group_name,
                itemList = listOf(stringResource(id = R.string.do_you_delete_group_name)),
                onDismissRequest = { setActionType(ActionType.NONE) },
                confirmData = ButtonData(textId = R.string.ok) {
                    onGroupDeleted(item.id)
                },
                dismissData = ButtonData(textId = R.string.cancel) { setActionType(ActionType.NONE) }
            )
        }

        ActionType.MANAGE_CONTACTS -> {
            setUpContactsPicking(item)
            navigate(Destination.PICKER.route)
        }

        ActionType.NONE -> Unit
    }
}

private enum class ActionType {
    NONE, CONTACTS, EDIT, DELETE, MANAGE_CONTACTS
}
