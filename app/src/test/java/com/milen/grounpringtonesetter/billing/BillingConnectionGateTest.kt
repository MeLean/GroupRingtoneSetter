package com.milen.grounpringtonesetter.billing

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingConnectionGateTest {

    @Test
    fun `timed out connection is cleared so the retry can start a new connection`() {
        val gate = BillingConnectionGate()
        val timedOutConnection = CompletableDeferred<Unit>()
        val retryConnection = CompletableDeferred<Unit>()

        assertTrue(gate.tryStart(timedOutConnection))
        assertTrue(gate.clear(timedOutConnection))
        assertTrue(gate.tryStart(retryConnection))
    }

    @Test
    fun `late callback cannot clear a newer connection`() {
        val gate = BillingConnectionGate()
        val timedOutConnection = CompletableDeferred<Unit>()
        val retryConnection = CompletableDeferred<Unit>()

        gate.tryStart(timedOutConnection)
        gate.clear(timedOutConnection)
        gate.tryStart(retryConnection)

        assertFalse(gate.clear(timedOutConnection))
        assertTrue(gate.current() === retryConnection)
    }
}
