package com.milen.grounpringtonesetter.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ContentProviderBatchUtilsTest {

    @Test
    fun `chunkBySize returns empty list when input is empty`() {
        val chunks = chunkBySize(emptyList<Int>(), maxChunkSize = 400)

        assertEquals(0, chunks.size)
    }

    @Test
    fun `chunkBySize returns single chunk when below limit`() {
        val items = (1..278).toList()

        val chunks = chunkBySize(items, maxChunkSize = 400)

        assertEquals(1, chunks.size)
        assertEquals(278, chunks.first().size)
    }

    @Test
    fun `chunkBySize splits list at max size boundary`() {
        val items = (1..1000).toList()

        val chunks = chunkBySize(items, maxChunkSize = 400)

        assertEquals(listOf(400, 400, 200), chunks.map { it.size })
    }

    @Test
    fun `chunkBySize throws when maxChunkSize is not positive`() {
        try {
            chunkBySize(listOf(1), maxChunkSize = 0)
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
