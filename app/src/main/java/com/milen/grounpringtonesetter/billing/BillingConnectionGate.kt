package com.milen.grounpringtonesetter.billing

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference

internal class BillingConnectionGate {

    private val inFlight = AtomicReference<CompletableDeferred<Unit>?>(null)

    fun current(): CompletableDeferred<Unit>? = inFlight.get()

    fun tryStart(connection: CompletableDeferred<Unit>): Boolean =
        inFlight.compareAndSet(null, connection)

    fun clear(connection: CompletableDeferred<Unit>): Boolean =
        inFlight.compareAndSet(connection, null)
}
