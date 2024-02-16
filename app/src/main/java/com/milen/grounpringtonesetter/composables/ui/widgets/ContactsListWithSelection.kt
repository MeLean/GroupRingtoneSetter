package com.milen.grounpringtonesetter.composables.ui.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.composables.ui.texts.TextWidget
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.screens.picker.data.PickerResultData

@Composable
fun ContactsListWithSelection(
    groupId: Long,
    allContacts: List<Contact>,
    currentGroupContacts: List<Contact>,
    onSelectionChange: (PickerResultData.ContactsGroupChanged) -> Unit
) {
    val selectedContacts =
        remember { mutableStateListOf<Contact>().also { it.addAll(currentGroupContacts) } }
    val initialSelection = remember { currentGroupContacts.map { it.id }.toSet() }

    LaunchedEffect(selectedContacts.size) {
        val includedContacts = selectedContacts.filter { it.id !in initialSelection }
        val excludedContacts = currentGroupContacts.filter {
            it.id !in selectedContacts.map { contact -> contact.id }.toSet()
        }

        onSelectionChange(
            PickerResultData.ContactsGroupChanged(
                groupId = groupId,
                includedContacts = includedContacts,
                excludedContacts = excludedContacts
            )
        )
    }

    LazyColumn {
        items(items = allContacts, key = { contact -> contact.id }) { contact ->
            val isSelected = selectedContacts.any { it.id == contact.id }
            ContactRow(
                contact = contact,
                isSelected = isSelected,
                onSelectionChanged = { selected ->
                    if (selected) {
                        if (!selectedContacts.any { it.id == contact.id }) {
                            selectedContacts.add(contact)
                        }
                    } else {
                        selectedContacts.removeAll { it.id == contact.id }
                    }
                }
            )
        }
    }
}

@Composable
fun ContactRow(
    contact: Contact,
    isSelected: Boolean,
    onSelectionChanged: (Boolean) -> Unit
) {
    BorderedRoundedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
            ) {
                TextWidget(text = contact.name)
                TextWidget(
                    text = contact.phone.orEmpty(),
                    style = MaterialTheme.typography.subtitle1
                )
            }

            Checkbox(
                checked = isSelected,
                onCheckedChange = onSelectionChanged,
                colors = CheckboxDefaults.colors(
                    checkedColor = colorResource(id = R.color.purple_200),
                    uncheckedColor = colorResource(id = R.color.textColor),
                    checkmarkColor = colorResource(id = R.color.textColor),
                )
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
}