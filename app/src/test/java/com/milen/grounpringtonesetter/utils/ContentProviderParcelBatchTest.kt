package com.milen.grounpringtonesetter.utils

import android.content.ContentProviderOperation
import android.os.Parcel
import android.provider.ContactsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContentProviderParcelBatchTest {

    @Test
    fun `large selection arguments split below parcel budget`() {
        val operations = (1..400).map { id ->
            ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection("contact_id = ?", arrayOf("$id" + "a".repeat(4_000)))
                .build()
        }

        val chunks = chunkBySizeAndBytes(
            operations,
            maxChunkSize = 400,
            maxChunkBytes = 256 * 1024,
            itemBytes = ::parcelSize,
        )

        assertTrue(chunks.size > 1)
        assertEquals(operations, chunks.flatten())
        assertTrue(chunks.all { chunk -> chunk.sumOf(::parcelSize) <= 256 * 1024 })
    }

    private fun parcelSize(operation: ContentProviderOperation): Int {
        val parcel = Parcel.obtain()
        return try {
            operation.writeToParcel(parcel, 0)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }
}
