package com.milen.grounpringtonesetter.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.utils.ContactRingtoneUpdateHelper
import com.milen.grounpringtonesetter.utils.ContactsHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactsProviderContractTest {

    @get:Rule
    val contactsPermissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
    )

    @Test
    fun insertedOnDeviceContactIsReadThroughProductionContactsGateway() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val displayName = "Codex Contract Contact"
        val rawContactUri = resolver.insert(
            RawContacts.CONTENT_URI,
            ContentValues().apply {
                putNull(RawContacts.ACCOUNT_NAME)
                putNull(RawContacts.ACCOUNT_TYPE)
            },
        )
        val rawContactId = requireNotNull(rawContactUri) {
            "ContactsProvider did not create the test raw contact"
        }.let(ContentUris::parseId)

        try {
            resolver.insert(
                Data.CONTENT_URI,
                ContentValues().apply {
                    put(Data.RAW_CONTACT_ID, rawContactId)
                    put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    put(StructuredName.DISPLAY_NAME, displayName)
                },
            )
            resolver.insert(
                Data.CONTENT_URI,
                ContentValues().apply {
                    put(Data.RAW_CONTACT_ID, rawContactId)
                    put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    put(Phone.NUMBER, "+359100000099")
                    put(Phone.TYPE, Phone.TYPE_MOBILE)
                },
            )

            val app = context.applicationContext as App
            val helper = ContactsHelper(
                appContext = app,
                preferenceHelper = app.preferencesHelper,
                contactRingtoneUpdateHelper = ContactRingtoneUpdateHelper(
                    tracker = app.tracker,
                    preferenceHelper = app.preferencesHelper,
                ),
                tracker = app.tracker,
            )

            val contacts = helper.getAllPhoneContacts(accountId = null)
            val inserted = contacts.single { it.name == displayName }
            assertEquals("+359100000099", inserted.phone)
            assertTrue(inserted.lookupKey.isNotBlank())
        } finally {
            resolver.delete(
                ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId),
                null,
                null,
            )
        }
    }
}
