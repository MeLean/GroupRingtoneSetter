package com.milen.grounpringtonesetter.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLabelRecoveryTest {

    @Test
    fun `recoverLocalLabels rebuilds labels from mirrored assignments when store is empty`() {
        val mirrored = listOf(
            MirroredLocalLabelAssignment(
                labelId = "family-id",
                labelName = "Family",
                lookupKey = "lookup-a",
                contactId = 1L
            ),
            MirroredLocalLabelAssignment(
                labelId = "family-id",
                labelName = "Family",
                lookupKey = "lookup-b",
                contactId = 2L
            ),
            MirroredLocalLabelAssignment(
                labelId = null,
                labelName = "Gym",
                lookupKey = "lookup-c",
                contactId = 3L
            )
        )

        val recovered = recoverLocalLabels(
            stored = LocalLabelDocument(),
            mirrored = mirrored,
            idGenerator = { "generated-gym-id" }
        )

        assertEquals(2, recovered.labels.size)
        assertEquals("family-id", recovered.labels[0].id)
        assertEquals(listOf("lookup-a", "lookup-b"), recovered.labels[0].members.map { it.lookupKey })
        assertEquals("generated-gym-id", recovered.labels[1].id)
        assertEquals("Gym", recovered.labels[1].name)
        assertEquals(listOf("lookup-c"), recovered.labels[1].members.map { it.lookupKey })
    }

    @Test
    fun `recoverLocalLabels keeps stored labels authoritative while filling missing assignments`() {
        val stored = LocalLabelDocument(
            labels = listOf(
                LocalStoredLabel(
                    id = "family-id",
                    name = "Family",
                    members = listOf(
                        LocalStoredLabelMember(
                            lookupKey = "lookup-a",
                            contactId = 1L
                        )
                    )
                )
            )
        )
        val mirrored = listOf(
            MirroredLocalLabelAssignment(
                labelId = "family-id",
                labelName = "Family renamed elsewhere",
                lookupKey = "lookup-b",
                contactId = 2L
            ),
            MirroredLocalLabelAssignment(
                labelId = null,
                labelName = "Family",
                lookupKey = "lookup-c",
                contactId = 3L
            ),
            MirroredLocalLabelAssignment(
                labelId = null,
                labelName = "Work",
                lookupKey = "lookup-d",
                contactId = 4L
            )
        )

        val recovered = recoverLocalLabels(
            stored = stored,
            mirrored = mirrored,
            idGenerator = { "generated-work-id" }
        )

        assertEquals(2, recovered.labels.size)
        assertEquals("Family", recovered.labels[0].name)
        assertEquals(
            listOf("lookup-a", "lookup-b", "lookup-c"),
            recovered.labels[0].members.map { it.lookupKey }
        )
        assertEquals("generated-work-id", recovered.labels[1].id)
        assertEquals("Work", recovered.labels[1].name)
        assertEquals(listOf("lookup-d"), recovered.labels[1].members.map { it.lookupKey })
    }

    @Test
    fun `recoverLocalLabels matches stored labels by trimmed mirrored name`() {
        val stored = LocalLabelDocument(
            labels = listOf(
                LocalStoredLabel(
                    id = "family-id",
                    name = "Family",
                    members = emptyList()
                )
            )
        )
        val mirrored = listOf(
            MirroredLocalLabelAssignment(
                labelId = null,
                labelName = "  Family  ",
                lookupKey = "lookup-a",
                contactId = 1L
            ),
            MirroredLocalLabelAssignment(
                labelId = null,
                labelName = "  Work  ",
                lookupKey = "lookup-b",
                contactId = 2L
            )
        )

        val recovered = recoverLocalLabels(
            stored = stored,
            mirrored = mirrored,
            idGenerator = { "generated-work-id" }
        )

        assertEquals(2, recovered.labels.size)
        assertEquals("Family", recovered.labels[0].name)
        assertEquals(listOf("lookup-a"), recovered.labels[0].members.map { it.lookupKey })
        assertEquals("generated-work-id", recovered.labels[1].id)
        assertEquals("Work", recovered.labels[1].name)
        assertEquals(listOf("lookup-b"), recovered.labels[1].members.map { it.lookupKey })
    }

}
