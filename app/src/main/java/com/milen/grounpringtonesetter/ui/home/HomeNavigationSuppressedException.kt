package com.milen.grounpringtonesetter.ui.home

import com.milen.grounpringtonesetter.utils.GuardedNavigationFailureReason

internal class HomeNavigationSuppressedException(
    val eventName: String,
    val actionId: Int,
    val expectedDestinationId: Int,
    val actualDestinationId: Int?,
    val graphId: Int,
    val failureReason: GuardedNavigationFailureReason,
) : IllegalStateException(
    buildString {
        append("Home navigation suppressed.")
        append(" event=").append(eventName)
        append(" actionId=").append(actionId)
        append(" expectedDestinationId=").append(expectedDestinationId)
        append(" actualDestinationId=").append(actualDestinationId)
        append(" graphId=").append(graphId)
        append(" reason=").append(failureReason.name)
    }
)
