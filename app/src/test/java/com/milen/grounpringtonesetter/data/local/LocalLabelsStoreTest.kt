package com.milen.grounpringtonesetter.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalLabelsStoreTest {

    @Test
    fun `write and read round-trip preserves labels and members`() {
        runBlocking {
            val dataSource = FakeLocalLabelsDataSource()
            val store = LocalLabelsStore(dataSource)
            val document = LocalLabelDocument(
                labels = listOf(
                    LocalStoredLabel(
                        id = "family-id",
                        name = "Family & Friends",
                        members = listOf(
                            LocalStoredLabelMember(
                                lookupKey = "lookup-1",
                                contactId = 11L
                            ),
                            LocalStoredLabelMember(
                                lookupKey = "lookup-2",
                                contactId = 12L
                            )
                        )
                    )
                )
            )

            store.write(document)

            assertEquals(document, store.read())
        }
    }

    @Test
    fun `write removes persisted value when document is empty`() {
        runBlocking {
            val dataSource = FakeLocalLabelsDataSource()
            val store = LocalLabelsStore(dataSource)

            store.write(
                LocalLabelDocument(
                    labels = listOf(
                        LocalStoredLabel(
                            id = "family-id",
                            name = "Family",
                            members = emptyList()
                        )
                    )
                )
            )
            store.write(LocalLabelDocument())

            assertNull(dataSource.value)
        }
    }
}

private class FakeLocalLabelsDataSource : LocalLabelsDataSource {
    var value: String? = null

    override suspend fun read(key: String): String? = value

    override suspend fun write(key: String, value: String) {
        this.value = value
    }

    override suspend fun remove(key: String) {
        value = null
    }
}
