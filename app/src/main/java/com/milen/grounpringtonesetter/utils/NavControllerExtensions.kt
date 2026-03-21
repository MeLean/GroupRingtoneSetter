package com.milen.grounpringtonesetter.utils

import android.os.Bundle
import androidx.annotation.IdRes
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.navOptions

internal enum class GuardedNavigationFailureReason {
    NO_CURRENT_DESTINATION,
    GRAPH_ROOT,
    WRONG_DESTINATION,
}

internal data class GuardedNavigationFailure(
    val reason: GuardedNavigationFailureReason,
    @param:IdRes val expectedDestinationId: Int,
    @param:IdRes val actualDestinationId: Int?,
    @param:IdRes val graphId: Int,
    @param:IdRes val actionId: Int,
)

internal fun classifyNavigationFailure(
    @IdRes expectedDestinationId: Int,
    @IdRes actualDestinationId: Int?,
    @IdRes graphId: Int,
): GuardedNavigationFailureReason? =
    when {
        actualDestinationId == null -> GuardedNavigationFailureReason.NO_CURRENT_DESTINATION
        actualDestinationId == graphId -> GuardedNavigationFailureReason.GRAPH_ROOT
        actualDestinationId != expectedDestinationId -> GuardedNavigationFailureReason.WRONG_DESTINATION
        else -> null
    }

internal fun NavController.navigateSingleTop(
    @IdRes resId: Int,
    args: Bundle? = null,
    @IdRes popUpToId: Int? = null,
    inclusive: Boolean = false,
    builder: NavOptionsBuilder.() -> Unit = {},
) {
    navigate(resId, args, navOptions {
        launchSingleTop = true
        popUpToId?.let { popUpTo(it) { this.inclusive = inclusive } }
        builder()
    })
}

internal fun NavController.navigateIfCurrentDestination(
    @IdRes expectedDestinationId: Int,
    @IdRes actionId: Int,
    args: Bundle? = null,
): GuardedNavigationFailure? {
    val actualDestinationId = currentDestination?.id
    val graphId = graph.id
    val failureReason = classifyNavigationFailure(
        expectedDestinationId = expectedDestinationId,
        actualDestinationId = actualDestinationId,
        graphId = graphId
    ) ?: run {
        navigate(actionId, args)
        return null
    }

    return GuardedNavigationFailure(
        reason = failureReason,
        expectedDestinationId = expectedDestinationId,
        actualDestinationId = actualDestinationId,
        graphId = graphId,
        actionId = actionId
    )
}
