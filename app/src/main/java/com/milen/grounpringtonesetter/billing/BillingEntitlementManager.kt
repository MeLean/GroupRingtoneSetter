// File: app/src/main/java/com/milen/grounpringtonesetter/billing/BillingEntitlementManager.kt
package com.milen.grounpringtonesetter.billing

import BillingGuard
import android.app.Activity
import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.milen.grounpringtonesetter.utils.Tracker
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

internal class BillingEntitlementManager(
    app: Application,
    private val tracker: Tracker,
) : PurchasesUpdatedListener {

    private val productId = "remove_ads_forever"

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(EntitlementState.UNKNOWN)
    val state: kotlinx.coroutines.flow.StateFlow<EntitlementState> = _state

    private val grace = AdFreeGraceStore(app)

    @Volatile
    private var purchaseInProgress = AtomicBoolean(false)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 10_000L
        const val POST_CONNECTION_DELAY_MS = 200L
        const val INITIAL_RETRY_DELAY_MS = 300L
        const val MAX_RETRY_DELAY_MS = 2000L
        const val MAX_RETRY_ATTEMPTS = 3
    }

    private val client: BillingClient = BillingClient.newBuilder(app)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .setListener(this)
        .build()

    // Single-flight connection guard
    private val connectingRef = AtomicReference<CompletableDeferred<Unit>?>(null)

    fun end() {
        runCatching { client.endConnection() }
    }

    suspend fun start() = runCatching {
        val startTime = System.currentTimeMillis()
        tracker.trackEvent(
            "billing_start_called",
            mapOf(
                "current_state" to _state.value.name,
                "client_ready" to client.isReady,
                "product_id" to productId
            )
        )

        tracker.trackEvent(
            "billing_start_step_connect",
            mapOf("step" to "ensureConnectedWithRetry")
        )
        ensureConnectedWithRetry()
        tracker.trackEvent(
            "billing_start_step_connect_done",
            mapOf(
                "step" to "ensureConnectedWithRetry",
                "client_ready" to client.isReady,
                "elapsed_ms" to (System.currentTimeMillis() - startTime)
            )
        )

        tracker.trackEvent(
            "billing_start_step_delay",
            mapOf("delay_ms" to POST_CONNECTION_DELAY_MS)
        )
        delay(POST_CONNECTION_DELAY_MS)

        tracker.trackEvent("billing_start_step_query", mapOf("step" to "getAdFree"))
        getAdFree()
        tracker.trackEvent(
            "billing_start_step_query_done",
            mapOf(
                "step" to "getAdFree",
                "state" to _state.value.name,
                "elapsed_ms" to (System.currentTimeMillis() - startTime)
            )
        )
    }.onSuccess {
        tracker.trackEvent(
            "billing_start_ok",
            mapOf(
                "state" to _state.value.name,
                "client_ready" to client.isReady
            )
        )
    }.onFailure { e ->
        if (e is CancellationException) {
            tracker.trackEvent(
                "billing_start_cancelled",
                mapOf(
                    "error_type" to e::class.java.simpleName,
                    "error_message" to (e.message ?: "unknown"),
                    "client_ready" to client.isReady
                )
            )
            throw e
        }
        tracker.trackEvent(
            "billing_start_failed",
            mapOf(
                "error_type" to e::class.java.simpleName,
                "error_message" to (e.message ?: "unknown"),
                "client_ready" to client.isReady,
                "stack_trace" to (e.stackTraceToString().take(500))
            )
        )
        tracker.trackError(e)
    }.getOrNull()

    /**
     * SAFE purchase launch.
     * Never throws; always returns a BillingResponseCode.
     */
    suspend fun launchPurchase(activity: Activity): Int {
        val startTime = System.currentTimeMillis()
        tracker.trackEvent(
            "billing_launch_called",
            mapOf(
                "client_ready" to client.isReady,
                "purchase_in_progress" to purchaseInProgress.get()
            )
        )

        try {
            ensureConnectedWithRetry()
            if (!client.isReady) {
                val errorCode = BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
                tracker.trackEvent(
                    "billing_launch_client_not_ready_after_connect",
                    mapOf(
                        "client_ready" to client.isReady,
                        "rc" to rcName(errorCode),
                        "rc_code" to errorCode,
                        "elapsed_ms" to (System.currentTimeMillis() - startTime),
                        "product_id" to productId
                    )
                )
                tracker.trackError(IllegalStateException("Billing client not ready after connect attempt"))
                return errorCode
            }
            delay(POST_CONNECTION_DELAY_MS)
        } catch (e: Exception) {
            tracker.trackEvent(
                "billing_launch_connect_failed",
                mapOf(
                    "error_type" to e::class.java.simpleName,
                    "error_message" to (e.message ?: "unknown"),
                    "elapsed_ms" to (System.currentTimeMillis() - startTime)
                )
            )
            tracker.trackError(e)
            return BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
        }

        // 2) Foreground/valid Activity
        val resumed =
            (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        val destroyed = runCatching { activity.isDestroyed }.getOrDefault(false)
        tracker.trackEvent(
            "billing_prelaunch_check", mapOf(
                "in_progress" to purchaseInProgress.get(),
                "resumed" to resumed,
                "finishing" to activity.isFinishing,
                "destroyed" to destroyed
            )
        )
        if (!resumed || activity.isFinishing || destroyed) {
            val errorCode = BillingClient.BillingResponseCode.ERROR
            tracker.trackEvent(
                "billing_prelaunch_invalid_activity",
                mapOf(
                    "resumed" to resumed,
                    "finishing" to activity.isFinishing,
                    "destroyed" to destroyed,
                    "rc" to rcName(errorCode),
                    "rc_code" to errorCode,
                    "elapsed_ms" to (System.currentTimeMillis() - startTime),
                    "product_id" to productId
                )
            )
            tracker.trackError(IllegalStateException("Activity invalid: resumed=$resumed, finishing=${activity.isFinishing}, destroyed=$destroyed"))
            return errorCode
        }

        // 3) Debounce
        if (!purchaseInProgress.compareAndSet(false, true)) {
            val errorCode = BillingClient.BillingResponseCode.DEVELOPER_ERROR
            tracker.trackEvent(
                "billing_already_in_progress",
                mapOf(
                    "rc" to rcName(errorCode),
                    "rc_code" to errorCode,
                    "elapsed_ms" to (System.currentTimeMillis() - startTime),
                    "product_id" to productId
                )
            )
            return errorCode
        }

        try {
            if (!client.isReady) {
                tracker.trackEvent(
                    "billing_launch_client_not_ready_before_query",
                    mapOf("client_ready" to client.isReady)
                )
                try {
                    ensureConnectedWithRetry()
                } catch (e: Exception) {
                    tracker.trackEvent(
                        "billing_launch_reconnect_failed",
                        mapOf(
                            "error_type" to e::class.java.simpleName,
                            "error_message" to (e.message ?: "unknown")
                        )
                    )
                    tracker.trackError(e)
                    return BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
                }
            }

            val pd = queryProductDetailsWithRetry(productId)
            if (pd == null) {
                val errorCode = BillingClient.BillingResponseCode.ITEM_UNAVAILABLE
                tracker.trackEvent(
                    "billing_pd_null",
                    mapOf(
                        "product_id" to productId,
                        "client_ready" to client.isReady,
                        "elapsed_ms" to (System.currentTimeMillis() - startTime),
                        "rc" to rcName(errorCode),
                        "rc_code" to errorCode
                    )
                )
                tracker.trackError(IllegalStateException("Product details null for product_id=$productId"))
                return errorCode
            }
            if (!pd.isUsableInapp()) {
                val errorCode = BillingClient.BillingResponseCode.ITEM_UNAVAILABLE
                tracker.trackEvent(
                    "billing_pd_not_sellable",
                    mapOf(
                        "product_id" to productId,
                        "has_one_time" to (pd.oneTimePurchaseOfferDetails != null),
                        "rc" to rcName(errorCode),
                        "rc_code" to errorCode,
                        "elapsed_ms" to (System.currentTimeMillis() - startTime)
                    )
                )
                tracker.trackError(IllegalStateException("Product not sellable: product_id=$productId, has_one_time=${pd.oneTimePurchaseOfferDetails != null}"))
                return errorCode
            }

            val flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(pd)
                            .build()
                    )
                )
                .build()

            if (!client.isReady) {
                tracker.trackEvent(
                    "billing_launch_client_not_ready_before_launch",
                    mapOf("client_ready" to client.isReady)
                )
                try {
                    ensureConnectedWithRetry()
                } catch (e: Exception) {
                    tracker.trackEvent(
                        "billing_launch_final_reconnect_failed",
                        mapOf(
                            "error_type" to e::class.java.simpleName,
                            "error_message" to (e.message ?: "unknown")
                        )
                    )
                    tracker.trackError(e)
                    return BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
                }
            }

            val stillResumedBeforeLaunch =
                (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
            val stillDestroyedBeforeLaunch =
                runCatching { activity.isDestroyed }.getOrDefault(false)
            val hasWindowFocus = runCatching { activity.hasWindowFocus() }.getOrDefault(false)
            val isVisible =
                runCatching { activity.window?.decorView?.isShown == true }.getOrDefault(false)

            if (!stillResumedBeforeLaunch || activity.isFinishing || stillDestroyedBeforeLaunch || !hasWindowFocus) {
                val errorCode = BillingClient.BillingResponseCode.ERROR
                val reason = when {
                    !stillResumedBeforeLaunch -> "not_resumed"
                    activity.isFinishing -> "finishing"
                    stillDestroyedBeforeLaunch -> "destroyed"
                    !hasWindowFocus -> "no_window_focus"
                    else -> "unknown"
                }
                tracker.trackEvent(
                    "billing_launch_activity_invalid_before_launch",
                    mapOf(
                        "resumed" to stillResumedBeforeLaunch,
                        "destroyed" to stillDestroyedBeforeLaunch,
                        "has_window_focus" to hasWindowFocus,
                        "is_visible" to isVisible,
                        "reason" to reason,
                        "rc" to rcName(errorCode),
                        "rc_code" to errorCode,
                        "elapsed_ms" to (System.currentTimeMillis() - startTime),
                        "product_id" to productId
                    )
                )
                tracker.trackError(IllegalStateException("Activity invalid before launch: $reason"))
                return errorCode
            }

            delay(100)

            BillingGuard.beginLaunch()

            val immediate = try {
                withContext(Dispatchers.Main) {
                    val launchStart = System.currentTimeMillis()
                    val stillResumedAtLaunch =
                        (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
                    val stillDestroyedAtLaunch =
                        runCatching { activity.isDestroyed }.getOrDefault(false)
                    val hasWindowFocusAtLaunch =
                        runCatching { activity.hasWindowFocus() }.getOrDefault(false)
                    val isVisibleAtLaunch =
                        runCatching { activity.window?.decorView?.isShown == true }.getOrDefault(
                            false
                        )

                    if (!stillResumedAtLaunch || activity.isFinishing || stillDestroyedAtLaunch || !hasWindowFocusAtLaunch) {
                        tracker.trackEvent(
                            "billing_launch_activity_invalid_at_launch",
                            mapOf(
                                "resumed" to stillResumedAtLaunch,
                                "destroyed" to stillDestroyedAtLaunch,
                                "has_window_focus" to hasWindowFocusAtLaunch,
                                "is_visible" to isVisibleAtLaunch
                            )
                        )
                        throw IllegalStateException("Activity no longer valid for billing launch")
                    }

                    runCatching {
                        activity.window?.decorView?.bringToFront()
                        activity.window?.decorView?.requestFocus()
                    }.onFailure { e ->
                        tracker.trackEvent(
                            "billing_launch_bring_to_front_failed",
                            mapOf(
                                "error_type" to e::class.java.simpleName,
                                "error_message" to (e.message ?: "unknown")
                            )
                        )
                    }

                    delay(50)

                    val finalResumedCheck =
                        (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
                    val finalFocusCheck =
                        runCatching { activity.hasWindowFocus() }.getOrDefault(false)

                    if (!finalResumedCheck || !finalFocusCheck) {
                        tracker.trackEvent(
                            "billing_launch_activity_lost_focus_after_bring_to_front",
                            mapOf(
                                "resumed" to finalResumedCheck,
                                "has_window_focus" to finalFocusCheck
                            )
                        )
                        throw IllegalStateException("Activity lost focus after bringToFront: resumed=$finalResumedCheck, hasFocus=$finalFocusCheck")
                    }

                    val result = client.launchBillingFlow(activity, flow)
                    tracker.trackEvent(
                        "billing_launch_flow_called",
                        mapOf(
                            "elapsed_ms" to (System.currentTimeMillis() - launchStart),
                            "client_ready" to client.isReady
                        )
                    )
                    result
                }
            } catch (e: Exception) {
                tracker.trackEvent(
                    "billing_launch_flow_exception",
                    mapOf(
                        "error_type" to e::class.java.simpleName,
                        "error_message" to (e.message ?: "unknown"),
                        "elapsed_ms" to (System.currentTimeMillis() - startTime)
                    )
                )
                tracker.trackError(e)
                BillingGuard.endLaunch()
                return BillingClient.BillingResponseCode.ERROR
            }

            val billingError = BillingError.fromBillingResult(immediate)

            val postLaunchResumed = withContext(Dispatchers.Main) {
                (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
            }
            val postLaunchFocus = withContext(Dispatchers.Main) {
                runCatching { activity.hasWindowFocus() }.getOrDefault(false)
            }

            tracker.trackEvent(
                "billing_launch_result",
                mapOf(
                    "rc" to rcName(immediate.responseCode),
                    "rc_code" to immediate.responseCode,
                    "msg" to (immediate.debugMessage),
                    "error_category" to billingError.category.name,
                    "elapsed_ms" to (System.currentTimeMillis() - startTime),
                    "client_ready" to client.isReady,
                    "post_launch_resumed" to postLaunchResumed,
                    "post_launch_focus" to postLaunchFocus
                )
            )

            if (immediate.responseCode == BillingClient.BillingResponseCode.OK) {
                // Normal handoff to Play purchase UI typically backgrounds/pauses current activity.
                if (postLaunchResumed && postLaunchFocus) {
                    tracker.trackEvent(
                        "billing_launch_ok_but_activity_still_foreground",
                        mapOf(
                            "rc" to rcName(immediate.responseCode),
                            "rc_code" to immediate.responseCode,
                            "post_launch_resumed" to postLaunchResumed,
                            "post_launch_focus" to postLaunchFocus,
                            "product_id" to productId
                        )
                    )
                    tracker.trackError(
                        IllegalStateException(
                            "Billing launch returned OK but activity stayed foregrounded: resumed=$postLaunchResumed, focus=$postLaunchFocus"
                        )
                    )
                } else {
                    tracker.trackEvent(
                        "billing_launch_ok_handoff_started",
                        mapOf(
                            "post_launch_resumed" to postLaunchResumed,
                            "post_launch_focus" to postLaunchFocus,
                            "product_id" to productId
                        )
                    )
                }
            }

            if (immediate.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                tracker.trackEvent(
                    "billing_launch_item_already_owned",
                    mapOf(
                        "rc" to rcName(immediate.responseCode),
                        "rc_code" to immediate.responseCode,
                        "msg" to (immediate.debugMessage),
                        "product_id" to productId,
                        "elapsed_ms" to (System.currentTimeMillis() - startTime)
                    )
                )
                getAdFree()
            }

            // 6) Retry once on transient unavailability
            if (immediate.responseCode == BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
                || immediate.responseCode == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
            ) {
                tracker.trackEvent(
                    "billing_launch_retry_triggered",
                    mapOf(
                        "rc" to rcName(immediate.responseCode),
                        "rc_code" to immediate.responseCode,
                        "msg" to (immediate.debugMessage),
                        "product_id" to productId,
                        "elapsed_ms" to (System.currentTimeMillis() - startTime)
                    )
                )
                delay(400)
                val stillResumed =
                    (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
                val stillDestroyed = runCatching { activity.isDestroyed }.getOrDefault(false)
                if (!stillResumed || activity.isFinishing || stillDestroyed) {
                    tracker.trackEvent(
                        "billing_launch_retry_activity_invalid",
                        mapOf("resumed" to stillResumed, "destroyed" to stillDestroyed)
                    )
                    return immediate.responseCode
                }
                try {
                    ensureConnectedWithRetry()
                } catch (e: Exception) {
                    tracker.trackEvent(
                        "billing_launch_retry_reconnect_failed",
                        mapOf(
                            "error_type" to e::class.java.simpleName,
                            "error_message" to (e.message ?: "unknown"),
                            "rc" to rcName(immediate.responseCode),
                            "rc_code" to immediate.responseCode,
                            "product_id" to productId
                        )
                    )
                    tracker.trackError(e)
                    return immediate.responseCode
                }
                BillingGuard.beginLaunch()
                val retry = try {
                    withContext(Dispatchers.Main) {
                        val stillResumed2 =
                            (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(
                                Lifecycle.State.RESUMED
                            ) == true
                        val stillDestroyed2 =
                            runCatching { activity.isDestroyed }.getOrDefault(false)
                        if (!stillResumed2 || activity.isFinishing || stillDestroyed2) {
                            tracker.trackEvent(
                                "billing_launch_retry_activity_invalid_at_launch",
                                mapOf("resumed" to stillResumed2, "destroyed" to stillDestroyed2)
                            )
                            throw IllegalStateException("Activity no longer valid for billing launch retry")
                        }
                        client.launchBillingFlow(activity, flow)
                    }
                } catch (e: Exception) {
                    tracker.trackEvent(
                        "billing_launch_retry_exception",
                        mapOf(
                            "error_type" to e::class.java.simpleName,
                            "error_message" to (e.message ?: "unknown"),
                            "rc" to rcName(immediate.responseCode),
                            "rc_code" to immediate.responseCode,
                            "product_id" to productId
                        )
                    )
                    tracker.trackError(e)
                    BillingGuard.endLaunch()
                    return BillingClient.BillingResponseCode.ERROR
                }
                val billingErrorRetry = BillingError.fromBillingResult(retry)
                tracker.trackEvent(
                    "billing_launch_retry_result", mapOf(
                        "rc" to rcName(retry.responseCode),
                        "rc_code" to retry.responseCode,
                        "msg" to (retry.debugMessage),
                        "error_category" to billingErrorRetry.category.name,
                        "product_id" to productId,
                        "elapsed_ms" to (System.currentTimeMillis() - startTime)
                    )
                )
                if (retry.responseCode != BillingClient.BillingResponseCode.OK) {
                    tracker.trackError(IllegalStateException("Billing launch retry failed: ${retry.responseCode} ${retry.debugMessage}"))
                }
                BillingGuard.endLaunch()
                return retry.responseCode
            }

            if (immediate.responseCode != BillingClient.BillingResponseCode.OK && immediate.responseCode != BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                tracker.trackError(IllegalStateException("Billing launch returned non-OK code: ${immediate.responseCode} ${immediate.debugMessage}"))
            }
            return immediate.responseCode
        } catch (t: Throwable) {
            tracker.trackEvent(
                "billing_launch_exception",
                mapOf(
                    "error_type" to t::class.java.simpleName,
                    "error_message" to (t.message ?: "unknown"),
                    "elapsed_ms" to (System.currentTimeMillis() - startTime),
                    "client_ready" to client.isReady
                )
            )
            tracker.trackError(t)
            return BillingClient.BillingResponseCode.ERROR
        } finally {
            BillingGuard.endLaunch()
            purchaseInProgress.set(false)
            tracker.trackEvent(
                "billing_launch_completed",
                mapOf("total_elapsed_ms" to (System.currentTimeMillis() - startTime))
            )
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        try {
            BillingGuard.endLaunch()
            purchaseInProgress.set(false)

            tracker.trackEvent(
                "billing_updates_callback", mapOf(
                    "rc" to rcName(result.responseCode),
                    "rc_code" to result.responseCode,
                    "msg" to (result.debugMessage),
                    "count" to (purchases?.size ?: 0)
                )
            )

            if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases.isNullOrEmpty()) {
                val billingError = BillingError.fromBillingResult(result)
                if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                    val wasExpectingLaunch = BillingGuard.isExpecting()
                    tracker.trackEvent(
                        "billing_updates_user_canceled",
                        mapOf(
                            "rc" to rcName(result.responseCode),
                            "rc_code" to result.responseCode,
                            "msg" to (result.debugMessage),
                            "purchases_count" to (purchases?.size ?: 0),
                            "was_expecting_launch" to wasExpectingLaunch,
                            "possible_background_block" to (wasExpectingLaunch && result.debugMessage.contains(
                                "closed"
                            ))
                        )
                    )
                    if (wasExpectingLaunch && result.debugMessage.contains("closed")) {
                        tracker.trackEvent(
                            "billing_background_launch_blocked_detected",
                            mapOf(
                                "rc" to rcName(result.responseCode),
                                "rc_code" to result.responseCode,
                                "msg" to (result.debugMessage),
                                "product_id" to productId
                            )
                        )
                        tracker.trackError(IllegalStateException("Billing UI likely blocked by Android background activity launch restriction: ${result.debugMessage}"))
                    }
                } else {
                    tracker.trackEvent(
                        "billing_updates_failed",
                        mapOf(
                            "rc" to rcName(result.responseCode),
                            "rc_code" to result.responseCode,
                            "msg" to (result.debugMessage),
                            "error_category" to billingError.category.name,
                            "purchases_count" to (purchases?.size ?: 0),
                            "product_id" to productId
                        )
                    )
                    tracker.trackError(IllegalStateException("Billing updates failed: ${result.responseCode} ${result.debugMessage}"))
                }
                return
            }

            val owns =
                purchases.any { it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            val pending =
                purchases.any { it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PENDING }
            if (pending) tracker.trackEvent("billing_purchase_pending")

            if (owns) {
                purchases.filter { it.products.contains(productId) && !it.isAcknowledged }
                    .forEach { p ->
                        val token = p.purchaseToken
                        fun ackOnce(cb: (BillingResult) -> Unit) {
                            val params =
                                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token)
                                    .build()
                            client.acknowledgePurchase(params, cb)
                        }
                        tracker.trackEvent(
                            "billing_ack_attempt",
                            mapOf("purchase_token_sig" to purchaseTokenSignature(token))
                        )
                        ackOnce { ackRes ->
                            try {
                                when (ackRes.responseCode) {
                                    BillingClient.BillingResponseCode.OK -> tracker.trackEvent(
                                        "billing_ack_result",
                                        mapOf("rc" to "OK")
                                    )

                                    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
                                        -> {
                                        tracker.trackEvent(
                                            "billing_ack_retry_due_transient",
                                            mapOf(
                                                "rc" to rcName(ackRes.responseCode),
                                                "rc_code" to ackRes.responseCode,
                                                "msg" to (ackRes.debugMessage),
                                                "purchase_token_sig" to purchaseTokenSignature(token)
                                            )
                                        )
                                        ackOnce { ackRes2 ->
                                            try {
                                                val billingError2 =
                                                    BillingError.fromBillingResult(ackRes2)
                                                tracker.trackEvent(
                                                    "billing_ack_result_retry", mapOf(
                                                        "rc" to rcName(ackRes2.responseCode),
                                                        "rc_code" to ackRes2.responseCode,
                                                        "msg" to (ackRes2.debugMessage),
                                                        "error_category" to billingError2.category.name,
                                                        "purchase_token_sig" to purchaseTokenSignature(token)
                                                    )
                                                )
                                                if (ackRes2.responseCode != BillingClient.BillingResponseCode.OK) {
                                                    tracker.trackError(IllegalStateException("Billing acknowledgment retry failed: ${ackRes2.responseCode} ${ackRes2.debugMessage}"))
                                                }
                                            } catch (t: Throwable) {
                                                tracker.trackEvent(
                                                    "billing_ack_retry_callback_exception",
                                                    mapOf(
                                                        "error_type" to t::class.java.simpleName,
                                                        "error_message" to (t.message ?: "unknown"),
                                                        "ack_rc" to rcName(ackRes2.responseCode)
                                                    )
                                                )
                                                tracker.trackError(t)
                                            }
                                        }
                                    }

                                    else -> {
                                        val billingErrorAck = BillingError.fromBillingResult(ackRes)
                                        tracker.trackEvent(
                                            "billing_ack_result_terminal", mapOf(
                                                "rc" to rcName(ackRes.responseCode),
                                                "rc_code" to ackRes.responseCode,
                                                "msg" to (ackRes.debugMessage),
                                                "error_category" to billingErrorAck.category.name,
                                                "purchase_token_sig" to purchaseTokenSignature(token)
                                            )
                                        )
                                        tracker.trackError(IllegalStateException("Billing acknowledgment failed: ${ackRes.responseCode} ${ackRes.debugMessage}"))
                                    }
                                }
                            } catch (t: Throwable) {
                                tracker.trackEvent(
                                    "billing_ack_callback_exception",
                                    mapOf(
                                        "error_type" to t::class.java.simpleName,
                                        "error_message" to (t.message ?: "unknown"),
                                        "ack_rc" to rcName(ackRes.responseCode)
                                    )
                                )
                                tracker.trackError(t)
                            }
                        }
                    }
                val until = System.currentTimeMillis() + AdFreeGraceStore.GRACE_TTL_MILLIS
                grace.saveAdFreeUntil(until)
                _state.value = EntitlementState.OWNED
                tracker.trackEvent("billing_entitlement_owned", mapOf("grace_until" to until))
            } else {
                grace.clear()
                _state.value = if (pending) EntitlementState.PENDING else EntitlementState.NOT_OWNED
                tracker.trackEvent("billing_entitlement_not_owned_on_query")
            }
        } catch (t: Throwable) {
            tracker.trackEvent(
                "billing_updates_callback_exception",
                mapOf(
                    "error_type" to t::class.java.simpleName,
                    "error_message" to (t.message ?: "unknown"),
                    "rc" to rcName(result.responseCode),
                    "rc_code" to result.responseCode
                )
            )
            tracker.trackError(t)
            // Ensure state is reset even on error
            try {
                BillingGuard.endLaunch()
                purchaseInProgress.set(false)
            } catch (_: Throwable) {
                // Ignore errors in cleanup
            }
        }
    }

    // ---- internals ----

    private suspend fun ensureConnectedWithRetry() {
        val startTime = System.currentTimeMillis()
        var attempt = 1
        var lastError: Throwable? = null
        var lastDelay = INITIAL_RETRY_DELAY_MS

        while (attempt <= MAX_RETRY_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            try {
                ensureConnected()
                if (attempt > 1) {
                    tracker.trackEvent(
                    "billing_connect_retry_success",
                        mapOf(
                            "attempt" to attempt,
                            "total_attempts" to attempt,
                            "elapsed_ms" to (System.currentTimeMillis() - startTime),
                            "client_ready" to client.isReady
                        )
                    )
                }
                return
            } catch (t: Throwable) {
                lastError = t
                val errorCategory = when {
                    t.message?.contains("SERVICE_DISCONNECTED") == true -> "SERVICE_DISCONNECTED"
                    t.message?.contains("SERVICE_UNAVAILABLE") == true -> "SERVICE_UNAVAILABLE"
                    t.message?.contains("timeout") == true -> "TIMEOUT"
                    else -> "UNKNOWN"
                }
                tracker.trackEvent(
                    "billing_connect_attempt_fail",
                    mapOf(
                        "attempt" to attempt,
                        "max_attempts" to MAX_RETRY_ATTEMPTS,
                        "error_type" to t::class.java.simpleName,
                        "error_message" to (t.message ?: "unknown"),
                        "error_category" to errorCategory,
                        "elapsed_ms" to (System.currentTimeMillis() - startTime),
                        "client_ready" to client.isReady,
                        "retry_delay_ms" to lastDelay
                    )
                )
                if (attempt == MAX_RETRY_ATTEMPTS) break
                delay(lastDelay)
                lastDelay = (lastDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                attempt++
            }
        }
        val finalError = lastError
            ?: IllegalStateException("Billing connect failed after $MAX_RETRY_ATTEMPTS attempts")
        tracker.trackEvent(
            "billing_connect_final_failure",
            mapOf(
                "total_attempts" to attempt,
                "total_elapsed_ms" to (System.currentTimeMillis() - startTime),
                "error_type" to finalError::class.java.simpleName,
                "error_message" to (finalError.message ?: "unknown")
            )
        )
        throw finalError
    }

    private suspend fun ensureConnected() {
        val connectStartTime = System.currentTimeMillis()
        if (client.isReady) {
            tracker.trackEvent(
                "billing_connect_already_ready",
                mapOf("client_ready" to client.isReady)
            )
            return
        }
        connectingRef.get()?.let { existing ->
            tracker.trackEvent("billing_connect_wait_existing")
            val waitStart = System.currentTimeMillis()
            awaitConnectionWithTimeout(
                deferred = existing,
                connectStartTime = connectStartTime,
                source = "existing"
            )
            tracker.trackEvent(
                "billing_connect_wait_completed",
                mapOf("wait_ms" to (System.currentTimeMillis() - waitStart))
            )
            return
        }
        val created = CompletableDeferred<Unit>()
        if (!connectingRef.compareAndSet(null, created)) {
            val winner = connectingRef.get()
            if (winner != null) {
                awaitConnectionWithTimeout(
                    deferred = winner,
                    connectStartTime = connectStartTime,
                    source = "raced_existing"
                )
                return
            }
            if (client.isReady) return
            throw IllegalStateException("Billing connect state changed during acquisition.")
        }
        tracker.trackEvent(
            "billing_connect_start",
            mapOf("client_ready" to client.isReady)
        )
        val listener = object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                val currentRef = connectingRef.get()
                if (currentRef != created) {
                    tracker.trackEvent(
                        "billing_connect_callback_stale",
                        mapOf("rc" to rcName(r.responseCode))
                    )
                    return
                }
                connectingRef.set(null)
                val elapsed = System.currentTimeMillis() - connectStartTime
                val billingError = BillingError.fromBillingResult(r)

                if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                    tracker.trackEvent(
                        "billing_connect_ok",
                        mapOf(
                            "elapsed_ms" to elapsed,
                            "client_ready" to client.isReady,
                            "debug_msg" to (r.debugMessage)
                        )
                    )
                    if (!created.isCompleted) {
                        created.complete(Unit)
                    } else {
                        tracker.trackEvent(
                            "billing_connect_callback_after_completion",
                            mapOf("elapsed_ms" to elapsed)
                        )
                    }
                } else if (r.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) {
                    tracker.trackEvent(
                        "billing_connect_service_disconnected",
                        mapOf(
                            "rc" to rcName(r.responseCode),
                            "rc_code" to r.responseCode,
                            "msg" to (r.debugMessage),
                            "error_category" to billingError.category.name,
                            "elapsed_ms" to elapsed
                        )
                    )
                    if (!created.isCompleted) {
                        created.completeExceptionally(
                            IllegalStateException("Billing connect failed: ${r.responseCode} ${r.debugMessage}")
                        )
                    } else {
                        tracker.trackEvent(
                            "billing_connect_callback_after_completion_disconnected",
                            mapOf("elapsed_ms" to elapsed)
                        )
                    }
                } else {
                    tracker.trackEvent(
                        "billing_connect_fail",
                        mapOf(
                            "rc" to rcName(r.responseCode),
                            "rc_code" to r.responseCode,
                            "msg" to (r.debugMessage),
                            "error_category" to billingError.category.name,
                            "elapsed_ms" to elapsed
                        )
                    )
                    if (!created.isCompleted) {
                        created.completeExceptionally(
                            IllegalStateException("Billing connect failed: ${r.responseCode} ${r.debugMessage}")
                        )
                    } else {
                        tracker.trackEvent(
                            "billing_connect_callback_after_completion_fail",
                            mapOf("elapsed_ms" to elapsed)
                        )
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                tracker.trackEvent(
                    "billing_connect_disconnected",
                    mapOf(
                        "elapsed_ms" to (System.currentTimeMillis() - connectStartTime),
                        "client_ready" to client.isReady
                    )
                )
                val currentRef = connectingRef.get()
                if (currentRef == created && !created.isCompleted) {
                    connectingRef.set(null)
                    tracker.trackEvent(
                        "billing_connect_disconnected_during_setup",
                        mapOf("elapsed_ms" to (System.currentTimeMillis() - connectStartTime))
                    )
                    created.completeExceptionally(
                        IllegalStateException("Billing connect failed: -1 Service connection is disconnected.")
                    )
                } else if (currentRef == created && created.isCompleted) {
                    tracker.trackEvent(
                        "billing_connect_disconnected_after_completion",
                        mapOf("elapsed_ms" to (System.currentTimeMillis() - connectStartTime))
                    )
                }
            }
        }
        client.startConnection(listener)
        awaitConnectionWithTimeout(
            deferred = created,
            connectStartTime = connectStartTime,
            source = "created"
        )
    }

    private suspend fun awaitConnectionWithTimeout(
        deferred: CompletableDeferred<Unit>,
        connectStartTime: Long,
        source: String,
    ) {
        val result = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
            deferred.await()
        }
        if (result != null) return

        tracker.trackEvent(
            "billing_connect_timeout",
            mapOf(
                "timeout_ms" to CONNECTION_TIMEOUT_MS,
                "elapsed_ms" to (System.currentTimeMillis() - connectStartTime),
                "client_ready" to client.isReady,
                "source" to source
            )
        )
        val timeoutError =
            IllegalStateException("Billing connection timeout after ${CONNECTION_TIMEOUT_MS}ms")
        tracker.trackError(timeoutError)
        throw timeoutError
    }

    private suspend fun getAdFree(): EntitlementState = suspendCancellableCoroutine { cont ->
        val queryStartTime = System.currentTimeMillis()
        tracker.trackEvent(
            "billing_query_purchases_start",
            mapOf(
                "client_ready" to client.isReady,
                "product_id" to productId
            )
        )
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { br, purchases ->
            try {
                if (!cont.isActive) {
                    tracker.trackEvent(
                        "billing_query_purchases_cancelled",
                        mapOf("elapsed_ms" to (System.currentTimeMillis() - queryStartTime))
                    )
                    return@queryPurchasesAsync
                }
            if (br.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) {
                tracker.trackEvent(
                    "billing_query_purchases_need_reconnect",
                    mapOf(
                        "rc" to rcName(br.responseCode),
                        "rc_code" to br.responseCode,
                        "elapsed_ms" to (System.currentTimeMillis() - queryStartTime)
                    )
                )
                ioScope.launch {
                    runCatching { ensureConnectedWithRetry() }.onSuccess {
                        client.queryPurchasesAsync(params) { br2, p2 ->
                            try {
                                tracker.trackEvent(
                                    "billing_query_purchases_retry_result", mapOf(
                                        "rc" to rcName(br2.responseCode),
                                        "rc_code" to br2.responseCode,
                                        "msg" to (br2.debugMessage),
                                        "count" to p2.size,
                                        "elapsed_ms" to (System.currentTimeMillis() - queryStartTime)
                                    )
                                )
                                handlePurchasesResultAfterOk(br2, p2, cont)
                            } catch (t: Throwable) {
                                tracker.trackEvent(
                                    "billing_query_purchases_retry_callback_exception",
                                    mapOf(
                                        "error_type" to t::class.java.simpleName,
                                        "error_message" to (t.message ?: "unknown"),
                                        "rc" to rcName(br2.responseCode)
                                    )
                                )
                                tracker.trackError(t)
                                cont.safeResume(EntitlementState.UNKNOWN)
                            }
                        }
                    }.onFailure { e ->
                        tracker.trackEvent(
                            "billing_query_purchases_retry_reconnect_failed",
                            mapOf(
                                "error_type" to e::class.java.simpleName,
                                "error_message" to (e.message ?: "unknown"),
                                "product_id" to productId,
                                "elapsed_ms" to (System.currentTimeMillis() - queryStartTime)
                            )
                        )
                        tracker.trackError(e)
                        cont.safeResume(EntitlementState.UNKNOWN)
                    }
                }
                return@queryPurchasesAsync
            }
                val billingError = BillingError.fromBillingResult(br)
            tracker.trackEvent(
                "billing_query_purchases_result", mapOf(
                    "rc" to rcName(br.responseCode),
                    "rc_code" to br.responseCode,
                    "msg" to (br.debugMessage),
                    "error_category" to billingError.category.name,
                    "count" to purchases.size,
                    "elapsed_ms" to (System.currentTimeMillis() - queryStartTime),
                    "product_id" to productId
                )
            )
                if (br.responseCode != BillingClient.BillingResponseCode.OK) {
                    tracker.trackError(IllegalStateException("Query purchases failed: ${br.responseCode} ${br.debugMessage}"))
                }
            handlePurchasesResultAfterOk(br, purchases, cont)
            } catch (t: Throwable) {
                tracker.trackEvent(
                    "billing_query_purchases_callback_exception",
                    mapOf(
                        "error_type" to t::class.java.simpleName,
                        "error_message" to (t.message ?: "unknown"),
                        "rc" to rcName(br.responseCode)
                    )
                )
                tracker.trackError(t)
                cont.safeResume(EntitlementState.UNKNOWN)
            }
        }
    }

    private suspend fun queryProductDetailsWithRetry(id: String): ProductDetails? {
        val startTime = System.currentTimeMillis()
        var attempt = 1
        var lastDelay = INITIAL_RETRY_DELAY_MS

        while (attempt <= MAX_RETRY_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            if (!client.isReady) {
                tracker.trackEvent(
                    "billing_pd_query_client_not_ready",
                    mapOf(
                        "attempt" to attempt,
                        "product_id" to id,
                        "client_ready" to client.isReady
                    )
                )
                try {
                    ensureConnectedWithRetry()
                    delay(POST_CONNECTION_DELAY_MS)
                } catch (e: Exception) {
                    tracker.trackEvent(
                        "billing_pd_query_reconnect_failed",
                        mapOf(
                            "attempt" to attempt,
                            "product_id" to id,
                            "error_type" to e::class.java.simpleName,
                            "error_message" to (e.message ?: "unknown")
                        )
                    )
                    tracker.trackError(e)
                    if (attempt == MAX_RETRY_ATTEMPTS) return null
                    delay(lastDelay)
                    lastDelay *= 2
                    attempt++
                    continue
                }
            }

            val result = queryProductDetails(id, attempt)
            if (result != null) {
                if (attempt > 1) {
                    tracker.trackEvent(
                        "billing_pd_query_retry_success",
                        mapOf(
                            "attempt" to attempt,
                            "product_id" to id,
                            "elapsed_ms" to (System.currentTimeMillis() - startTime)
                        )
                    )
                }
                return result
            }

            if (attempt == MAX_RETRY_ATTEMPTS) {
                tracker.trackEvent(
                    "billing_pd_query_final_failure",
                    mapOf(
                        "total_attempts" to attempt,
                        "product_id" to id,
                        "elapsed_ms" to (System.currentTimeMillis() - startTime)
                    )
                )
                return null
            }

            tracker.trackEvent(
                "billing_pd_query_retry_scheduled",
                mapOf(
                    "attempt" to attempt,
                    "next_attempt" to (attempt + 1),
                    "product_id" to id,
                    "retry_delay_ms" to lastDelay
                )
            )
            delay(lastDelay)
            lastDelay = (lastDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            attempt++
        }
        return null
    }

    private suspend fun queryProductDetails(
        id: String,
        attempt: Int,
    ): ProductDetails? = suspendCancellableCoroutine { cont ->
        tracker.trackEvent(
            "billing_pd_query_start",
            mapOf(
                "id" to id,
                "attempt" to attempt,
                "client_ready" to client.isReady
            )
        )
            val q = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(id)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    )
                ).build()
        val queryStart = System.currentTimeMillis()
            client.queryProductDetailsAsync(q) { br, result ->
                try {
                    if (!cont.isActive) {
                        tracker.trackEvent(
                            "billing_pd_query_cancelled",
                            mapOf("product_id" to id, "attempt" to attempt)
                        )
                        return@queryProductDetailsAsync
                    }
                    val list = result.productDetailsList
                    val elapsed = System.currentTimeMillis() - queryStart
                    val billingError = BillingError.fromBillingResult(br)

                    tracker.trackEvent(
                        "billing_pd_query_result",
                        mapOf(
                    "rc" to rcName(br.responseCode),
                            "rc_code" to br.responseCode,
                            "msg" to (br.debugMessage),
                            "error_category" to billingError.category.name,
                    "count" to list.size,
                            "ids" to list.joinToString { it.productId },
                            "elapsed_ms" to elapsed,
                            "attempt" to attempt,
                            "client_ready" to client.isReady
                        )
                    )

                    if (br.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) {
                        tracker.trackEvent(
                            "billing_pd_query_service_disconnected",
                            mapOf(
                                "product_id" to id,
                                "attempt" to attempt,
                                "elapsed_ms" to elapsed
                            )
                        )
                        cont.safeResume(null)
                        return@queryProductDetailsAsync
                    }

                    val errorCategory = billingError.category
                if (br.responseCode != BillingClient.BillingResponseCode.OK) {
                    if (errorCategory == BillingError.ErrorCategory.CONFIGURATION) {
                        tracker.trackEvent(
                            "billing_pd_query_permanent_error",
                            mapOf(
                                "product_id" to id,
                                "rc" to rcName(br.responseCode),
                                "rc_code" to br.responseCode,
                                "attempt" to attempt,
                                "error_category" to errorCategory.name
                            )
                        )
                        cont.safeResume(null)
                        return@queryProductDetailsAsync
                }
                    tracker.trackEvent(
                        "billing_pd_query_non_ok",
                        mapOf(
                            "product_id" to id,
                            "rc" to rcName(br.responseCode),
                            "rc_code" to br.responseCode,
                            "attempt" to attempt,
                            "error_category" to billingError.category.name
                        )
                    )
                    cont.safeResume(null)
                    return@queryProductDetailsAsync
                }

                val match = list.firstOrNull { it.productId == id }
                    if (match == null) {
                        tracker.trackEvent(
                            "billing_pd_match_null",
                            mapOf(
                                "id" to id,
                                "attempt" to attempt,
                                "returned_ids" to list.joinToString { it.productId },
                                "elapsed_ms" to elapsed
                            )
                        )
                    } else {
                        tracker.trackEvent(
                            "billing_pd_match_found",
                            mapOf(
                        "id" to match.productId,
                        "title" to match.title,
                                "price" to (match.oneTimePurchaseOfferDetails?.formattedPrice
                                    ?: "n/a"),
                                "has_one_time" to (match.oneTimePurchaseOfferDetails != null),
                                "attempt" to attempt,
                                "elapsed_ms" to elapsed
                    )
                )
                    }
                cont.safeResume(match)
                } catch (t: Throwable) {
                    tracker.trackEvent(
                        "billing_pd_query_callback_exception",
                        mapOf(
                            "error_type" to t::class.java.simpleName,
                            "error_message" to (t.message ?: "unknown"),
                            "product_id" to id,
                            "attempt" to attempt,
                            "rc" to rcName(br.responseCode)
                        )
                    )
                    tracker.trackError(t)
                    cont.safeResume(null)
                }
            }
        }

    private fun handlePurchasesResultAfterOk(
        br: BillingResult,
        purchases: List<Purchase>,
        cont: CancellableContinuation<EntitlementState>,
    ) {
        if (br.responseCode != BillingClient.BillingResponseCode.OK) {
            val billingError = BillingError.fromBillingResult(br)
            tracker.trackEvent(
                "billing_query_purchases_not_ok",
                mapOf(
                    "rc" to rcName(br.responseCode),
                    "rc_code" to br.responseCode,
                    "msg" to (br.debugMessage),
                    "error_category" to billingError.category.name,
                    "purchases_count" to purchases.size,
                    "product_id" to productId
                )
            )
            tracker.trackError(IllegalStateException("Query purchases failed: ${br.responseCode} ${br.debugMessage}"))
            cont.safeResume(EntitlementState.UNKNOWN)
            return
        }
        val owns =
            purchases.any { it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val pending =
            purchases.any { it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PENDING }
        if (pending) tracker.trackEvent("billing_query_purchases_pending")

        if (owns) {
            purchases.filter { it.products.contains(productId) && !it.isAcknowledged }.forEach {
                tracker.trackEvent(
                    "billing_ack_attempt_on_query",
                    mapOf("purchase_token_sig" to purchaseTokenSignature(it.purchaseToken))
                )
                client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder().setPurchaseToken(it.purchaseToken)
                        .build()
                ) { ackRes ->
                    try {
                        val billingErrorAck = BillingError.fromBillingResult(ackRes)
                        tracker.trackEvent(
                            "billing_ack_result_on_query",
                            mapOf(
                                "rc" to rcName(ackRes.responseCode),
                                "rc_code" to ackRes.responseCode,
                                "msg" to (ackRes.debugMessage),
                                "error_category" to billingErrorAck.category.name,
                                "purchase_token_sig" to purchaseTokenSignature(it.purchaseToken)
                            )
                        )
                        if (ackRes.responseCode != BillingClient.BillingResponseCode.OK) {
                            tracker.trackError(IllegalStateException("Acknowledgment on query failed: ${ackRes.responseCode} ${ackRes.debugMessage}"))
                        }
                    } catch (t: Throwable) {
                        tracker.trackEvent(
                            "billing_ack_on_query_callback_exception",
                            mapOf(
                                "error_type" to t::class.java.simpleName,
                                "error_message" to (t.message ?: "unknown"),
                                "ack_rc" to rcName(ackRes.responseCode)
                            )
                        )
                        tracker.trackError(t)
                    }
                }
            }
            val until = System.currentTimeMillis() + AdFreeGraceStore.GRACE_TTL_MILLIS
            grace.saveAdFreeUntil(until)
            _state.value = EntitlementState.OWNED
            tracker.trackEvent("billing_entitlement_owned_on_query", mapOf("grace_until" to until))
        } else {
            grace.clear()
            _state.value = if (pending) EntitlementState.PENDING else EntitlementState.NOT_OWNED
            tracker.trackEvent("billing_entitlement_not_owned_on_query")
        }
        cont.safeResume(_state.value)
    }

    private fun <T> CancellableContinuation<T>.safeResume(value: T) {
        if (isActive) resume(value)
    }

    private fun ProductDetails.isUsableInapp(): Boolean {
        return try {
            this.oneTimePurchaseOfferDetails != null
                    || (ProductDetails::class.java.getMethod("getOneTimePurchaseOfferDetailsList")
                .invoke(this) as? List<*>)?.isNotEmpty() == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun rcName(code: Int) = when (code) {
        BillingClient.BillingResponseCode.OK -> "OK"
        BillingClient.BillingResponseCode.USER_CANCELED -> "USER_CANCELED"
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE"
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "ITEM_UNAVAILABLE"
        BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
        BillingClient.BillingResponseCode.ERROR -> "ERROR"
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED"
        BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> "ITEM_NOT_OWNED"
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
        else -> "UNKNOWN_$code"
    }

    private fun purchaseTokenSignature(token: String): String {
        if (token.isBlank()) return "empty"
        return token.hashCode().toUInt().toString(16)
    }
}
