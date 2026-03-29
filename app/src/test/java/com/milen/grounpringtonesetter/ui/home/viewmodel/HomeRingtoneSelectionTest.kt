package com.milen.grounpringtonesetter.ui.home.viewmodel

import android.net.FakeUri
import android.net.Uri
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRingtoneSelectionTest {

    @Test
    fun `resolveSelectedGroupForRingtone returns current group by id regardless of order`() {
        val staleSelectedGroup = LabelItem(
            id = 20L,
            groupName = "Bravo",
            contacts = listOf(Contact(id = 2L, name = "Old", phone = null, ringtoneUriStr = null))
        )
        val currentGroup = staleSelectedGroup.copy(
            contacts = listOf(
                Contact(id = 2L, name = "Current", phone = "123", ringtoneUriStr = "tone://uri"),
                Contact(id = 3L, name = "Extra", phone = "456", ringtoneUriStr = "tone://uri")
            ),
            ringtoneUriList = listOf("tone://uri"),
            ringtoneFileName = "ringtone.mp3"
        )
        val labels = listOf(
            LabelItem(id = 30L, groupName = "Charlie", contacts = emptyList()),
            currentGroup,
            LabelItem(id = 10L, groupName = "Alpha", contacts = emptyList())
        )

        val resolved = resolveSelectedGroupForRingtone(
            labels = labels,
            selectedGroupId = staleSelectedGroup.id
        )

        assertSame(currentGroup, resolved)
    }

    @Test
    fun `resolveSelectedGroupForRingtone returns null when selection is missing`() {
        val resolved = resolveSelectedGroupForRingtone(
            labels = emptyList(),
            selectedGroupId = 42L
        )

        assertNull(resolved)
    }

    @Test
    fun `isUnsupportedGroupRingtoneUri rejects default ringtone alias`() {
        val uri: Uri = FakeUri("content://settings/system/ringtone")

        assertTrue(isUnsupportedGroupRingtoneUri(uri))
    }

    @Test
    fun `isUnsupportedGroupRingtoneUri rejects default notification and alarm aliases`() {
        val notificationUri: Uri = FakeUri("content://settings/system/notification_sound")
        val alarmUri: Uri = FakeUri("content://settings/system/alarm_alert")

        assertTrue(isUnsupportedGroupRingtoneUri(notificationUri))
        assertTrue(isUnsupportedGroupRingtoneUri(alarmUri))
    }

    @Test
    fun `isUnsupportedGroupRingtoneUri allows concrete media store ringtone`() {
        val uri: Uri = FakeUri("content://media/internal/audio/media/50")

        assertFalse(isUnsupportedGroupRingtoneUri(uri))
    }

    @Test
    fun `isUnsupportedGroupRingtoneUri allows file and saf uris`() {
        val fileUri: Uri = FakeUri("file:///sdcard/Ringtones/tone.mp3")
        val safUri: Uri =
            FakeUri("content://com.android.externalstorage.documents/document/audio%3A12")

        assertFalse(isUnsupportedGroupRingtoneUri(fileUri))
        assertFalse(isUnsupportedGroupRingtoneUri(safUri))
    }
}
