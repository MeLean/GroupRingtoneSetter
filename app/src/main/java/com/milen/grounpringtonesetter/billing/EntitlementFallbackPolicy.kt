package com.milen.grounpringtonesetter.billing

internal fun resolveEntitlementAfterBillingAttempt(
    current: EntitlementState,
    adFreeUntilMillis: Long?,
    nowMillis: Long,
): EntitlementState {
    if (current != EntitlementState.UNKNOWN) return current

    return if (adFreeUntilMillis != null && adFreeUntilMillis > nowMillis) {
        EntitlementState.OWNED
    } else {
        EntitlementState.NOT_OWNED
    }
}
