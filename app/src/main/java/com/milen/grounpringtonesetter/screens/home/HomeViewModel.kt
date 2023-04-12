package com.milen.grounpringtonesetter.screens.home

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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow

class HomeViewModel : ViewModel() {
    private val _uiState =
        MutableStateFlow(HomeScreenState())

    fun getHomeViewModelCallbacks(): HomeViewModelCallbacks =
        HomeViewModelCallbacks(
            uiState = _uiState,
            fetchLabels = ::fetchLabels,
            onSetRingtones = ::onSetRingTones,
            onRingtoneChosen = ::onRingtoneChosen,
            hideLoading = ::hideLoading
        )

    private fun onPermissionDenied() {

    }

    private fun hideLoading() {
        _uiState.tryEmit(
            _uiState.value.copy(
                isLoading = false,
                shouldShowAd = false
            )
        )
    }

    @Suppress("KotlinConstantConditions")
    private fun onSetRingTones(
        groupItems: MutableList<GroupItem>,
        contentResolver: ContentResolver,
        showAdd: Boolean = true
    ) {
        if (showAdd) {
            _uiState.tryEmit(
                _uiState.value.copy(
                    isLoading = true,
                    shouldShowAd = showAdd
                )
            )
        } else {
            with(groupItems) {
                showLoading()
                viewModelScope.async {
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
                            isAllDone = true,
                            shouldShowAd = showAdd
                        )
                    )
                }
            }
        }
    }

    private fun onRingtoneChosen(label: String, uri: Uri?) {
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

        viewModelScope.async {
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
