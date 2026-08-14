package com.milen.grounpringtonesetter.billing

import org.junit.Assert.assertEquals
import org.junit.Test

class EntitlementFallbackPolicyTest {

    @Test
    fun `active paid grace protects owner when billing is unavailable`() {
        val result = resolveEntitlementAfterBillingAttempt(
            current = EntitlementState.UNKNOWN,
            adFreeUntilMillis = 2_000L,
            nowMillis = 1_000L,
        )

        assertEquals(EntitlementState.OWNED, result)
    }

    @Test
    fun `unknown becomes not owned when billing is unavailable without active grace`() {
        val result = resolveEntitlementAfterBillingAttempt(
            current = EntitlementState.UNKNOWN,
            adFreeUntilMillis = null,
            nowMillis = 1_000L,
        )

        assertEquals(EntitlementState.NOT_OWNED, result)
    }

    @Test
    fun `expired paid grace does not block ads indefinitely`() {
        val result = resolveEntitlementAfterBillingAttempt(
            current = EntitlementState.UNKNOWN,
            adFreeUntilMillis = 1_000L,
            nowMillis = 1_000L,
        )

        assertEquals(EntitlementState.NOT_OWNED, result)
    }

    @Test
    fun `verified entitlement is never replaced by fallback`() {
        EntitlementState.entries
            .filterNot { it == EntitlementState.UNKNOWN }
            .forEach { current ->
                assertEquals(
                    current,
                    resolveEntitlementAfterBillingAttempt(
                        current = current,
                        adFreeUntilMillis = null,
                        nowMillis = 1_000L,
                    ),
                )
            }
    }
}
