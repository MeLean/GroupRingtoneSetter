package com.milen.grounpringtonesetter.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardedNavigationPolicyTest {

    @Test
    fun `null current destination is classified as missing destination`() {
        val failure = classifyNavigationFailure(
            expectedDestinationId = 1,
            actualDestinationId = null,
            graphId = 99
        )

        assertEquals(GuardedNavigationFailureReason.NO_CURRENT_DESTINATION, failure)
    }

    @Test
    fun `graph root destination is classified as graph root`() {
        val failure = classifyNavigationFailure(
            expectedDestinationId = 1,
            actualDestinationId = 99,
            graphId = 99
        )

        assertEquals(GuardedNavigationFailureReason.GRAPH_ROOT, failure)
    }

    @Test
    fun `different destination is classified as wrong destination`() {
        val failure = classifyNavigationFailure(
            expectedDestinationId = 1,
            actualDestinationId = 2,
            graphId = 99
        )

        assertEquals(GuardedNavigationFailureReason.WRONG_DESTINATION, failure)
    }

    @Test
    fun `expected destination is allowed`() {
        val failure = classifyNavigationFailure(
            expectedDestinationId = 1,
            actualDestinationId = 1,
            graphId = 99
        )

        assertNull(failure)
    }
}
