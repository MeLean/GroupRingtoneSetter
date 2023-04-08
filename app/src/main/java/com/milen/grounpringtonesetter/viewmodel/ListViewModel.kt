package com.milen.grounpringtonesetter.viewmodel

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.ContactsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.GroupItem
import com.milen.grounpringtonesetter.ui.screenstate.LabelListScreenState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ListViewModel : ViewModel() {
    private val _uiState: MutableStateFlow<LabelListScreenState> =
        MutableStateFlow(LabelListScreenState())
    val uiState: StateFlow<LabelListScreenState> = _uiState.asStateFlow()

    fun setRingTones(groupItems: MutableList<GroupItem>, contentResolver: ContentResolver): Unit =
        with(groupItems) {
            showLoading()

            viewModelScope.launch {
                map {
                    it.ringtoneUri?.let { uri ->
                        setRingToneToGroupName(
                            contentResolver = contentResolver,
                            groupName = it.groupName,
                            newRingtoneUri = uri
                        )
                    }
                }
                _uiState.tryEmit(
                    _uiState.value.copy(
                        isLoading = false,
                        isAllDone = true
                    )
                )
            }
        }

    fun onRingtoneChosen(label: String, uri: Uri?) {
        _uiState.value.groupItems.apply {
            firstOrNull { it.groupName == label }?.let {
                it.ringtoneUri = uri
            }

            _uiState.tryEmit(
                _uiState.value.copy(
                    id = System.currentTimeMillis(),
                    groupItems = this
                )
            )
        }
    }

    @SuppressLint("Range")
    fun fetchLabels(contentResolver: ContentResolver) {
        showLoading()

        viewModelScope.launch {
            mutableListOf<String>().apply {
                contentResolver.query(
                    ContactsContract.Groups.CONTENT_URI,
                    arrayOf(ContactsContract.Groups.TITLE),
                    null,
                    null,
                    null
                )?.use {
                    val titleColumn = it.getColumnIndex(ContactsContract.Groups.TITLE)
                    while (it.moveToNext()) {
                        val title = it.getString(titleColumn)
                        add(title)
                    }
                }

                updateLabels(distinct(), contentResolver)
            }
        }
    }

    private fun updateLabels(groups: List<String>, contentResolver: ContentResolver) {
        groups.map { groupName ->
            with(groupName.getContactsByGroupName(contentResolver)) {
                GroupItem(
                    groupName = groupName,
                    contacts = this,
                    ringtoneUri = allContactsHaveSameRingtoneUri()
                )
            }
        }
            .toMutableList()
            .run {
                _uiState.tryEmit(
                    _uiState.value.copy(
                        isLoading = false,
                        groupItems = this,
                        areLabelsFetched = true
                    )
                )
            }
    }


    @SuppressLint("Range")
    private fun String.getContactsByGroupName(
        contentResolver: ContentResolver
    ): List<Contact> {
        val groupProjection = arrayOf(ContactsContract.Groups._ID)
        val groupSelection = "${ContactsContract.Groups.TITLE} = ?"
        val groupSelectionArgs = arrayOf(this)
        val groupCursor = contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            groupProjection,
            groupSelection,
            groupSelectionArgs,
            null
        )

        var groupId = -1L
        if (groupCursor?.moveToFirst() == true) {
            groupId = groupCursor.getLong(groupCursor.getColumnIndex(ContactsContract.Groups._ID))
        }

        groupCursor?.close()

        val contactsUri = ContactsContract.Data.CONTENT_URI
        val contactProjection = arrayOf(
            ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.Contacts.CUSTOM_RINGTONE
        )
        val contactSelection =
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?"
        val contactSelectionArgs = arrayOf(
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
            groupId.toString()
        )
        val cursor = contentResolver.query(
            contactsUri,
            contactProjection,
            contactSelection,
            contactSelectionArgs,
            "${ContactsContract.Data.DISPLAY_NAME} ASC"
        )

        val contacts: MutableList<Contact> = mutableListOf()
        while (cursor?.moveToNext() == true) {

            val name = cursor.getString(cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME))
            val ringtoneUri =
                cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.CUSTOM_RINGTONE))
                    ?.let { Uri.parse(it) }

            contacts.add(Contact(name, ringtoneUri))
        }

        cursor?.close()

        return contacts
    }

    @SuppressLint("Range")
    private fun setRingToneToGroupName(
        contentResolver: ContentResolver,
        groupName: String,
        newRingtoneUri: Uri
    ) {
        val groupProjection = arrayOf(ContactsContract.Groups._ID)
        val groupSelection = "${ContactsContract.Groups.TITLE} = ?"
        val groupSelectionArgs = arrayOf(groupName)
        val groupCursor = contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            groupProjection,
            groupSelection,
            groupSelectionArgs,
            null
        )

        var groupId = -1L
        if (groupCursor?.moveToFirst() == true) {
            groupId = groupCursor.getLong(groupCursor.getColumnIndex(ContactsContract.Groups._ID))
        }

        groupCursor?.close()

        val contactsUri = ContactsContract.Data.CONTENT_URI
        val contactProjection = arrayOf(ContactsContract.Data.CONTACT_ID)
        val contactSelection =
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?"
        val contactSelectionArgs = arrayOf(
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
            groupId.toString()
        )
        val cursor = contentResolver.query(
            contactsUri,
            contactProjection,
            contactSelection,
            contactSelectionArgs,
            null
        )

        while (cursor?.moveToNext() == true) {

            val contactId =
                cursor.getString(cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID))
            val contactUri = ContentUris.withAppendedId(
                ContactsContract.Contacts.CONTENT_URI,
                contactId.toLong()
            )

            val values = ContentValues()
            values.put(ContactsContract.Contacts.CUSTOM_RINGTONE, newRingtoneUri.toString())
            contentResolver.update(contactUri, values, null, null)
        }

        cursor?.close()
    }


    private fun showLoading() {
        _uiState.tryEmit(
            _uiState.value.copy(
                id = System.currentTimeMillis(),
                isLoading = true
            )
        )
    }
}

private fun List<Contact>.allContactsHaveSameRingtoneUri(): Uri? =
    this.firstOrNull()?.ringtoneUri?.let { ringtoneUri ->
        if (all { contact -> contact.ringtoneUri == ringtoneUri })
            ringtoneUri else null
    }

