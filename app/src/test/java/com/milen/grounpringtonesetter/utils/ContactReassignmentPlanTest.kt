package com.milen.grounpringtonesetter.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactReassignmentPlanTest {

    @Test
    fun `raw contact resolved during validation is reused for execution`() {
        var resolutionCalls = 0

        val plan = buildContactReassignmentPlan(
            contactIds = listOf(7469L),
            isAlreadyAssigned = { false },
            resolveRawContactId = {
                resolutionCalls += 1
                if (resolutionCalls == 1) 9001L else null
            },
        )

        assertEquals(mapOf(7469L to 9001L), plan.rawContactIdsByContactId)
        assertEquals(1, resolutionCalls)
    }
}
