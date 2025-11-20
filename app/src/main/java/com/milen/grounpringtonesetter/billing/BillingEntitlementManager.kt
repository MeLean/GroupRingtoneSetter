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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
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
        .enableAutoServiceReconnection()
        .setListener(this)
        .build()

    // Single-flight connection guard
    private val connectingRef = AtomicReference<CompletableDeferred<Unit>?>(null)

    fun end() {
        runCatching { client.endConnection() }
    }

    suspend fun start() = runCatching {
        tracker.trackEvent("billing_start_called", mapOf("current_state" to _state.value.name))
        ensureConnectedWithRetry()
        delay(POST_CONNECTION_DELAY_MS)
        getAdFree()
    }.onSuccess {
        tracker.trackEvent(
            "billing_start_ok",
            mapOf(
                "state" to _state.value.name,
                "client_ready" to client.isReady
            )
        )
    }.onFailure { e ->
        if (e is CancellationException) throw e
        tracker.trackEvent(
            "billing_start_failed",
            mapOf(
                "error_type" to e::class.java.simpleName,
                "error_message" to (e.message ?: "unknown"),
                "client_ready" to client.isReady
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
                tracker.trackEvent(
                    "billing_launch_client_not_ready_after_connect",
                    mapOf("client_ready" to client.isReady)
                )
                return BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
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
            tracker.trackEvent("billing_prelaunch_invalid_activity")
            return BillingClient.BillingResponseCode.ERROR
        }

        // 3) Debounce
        if (!purchaseInProgress.compareAndSet(false, true)) {
            tracker.trackEvent("billing_already_in_progress")
            return BillingClient.BillingResponseCode.DEVELOPER_ERROR
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
                tracker.trackEvent(
                    "billing_pd_null",
                    mapOf(
                        "product_id" to productId,
                        "client_ready" to client.isReady,
                        "elapsed_ms" to (System.currentTimeMillis() - startTime)
                    )
                )
                return BillingClient.BillingResponseCode.ITEM_UNAVAILABLE
            }
            if (!pd.isUsableInapp()) {
                tracker.trackEvent(
                    "billing_pd_not_sellable",
                    mapOf(
                        "product_id" to productId,
                        "has_one_time" to (pd.oneTimePurchaseOfferDetails != null)
                    )
                )
                return BillingClient.BillingResponseCode.ITEM_UNAVAILABLE
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
            val stillDestroyedBeforeLaunch = runCatching { activity.isDestroyed }.getOrDefault(false)
            if (!stillResumedBeforeLaunch || activity.isFinishing || stillDestroyedBeforeLaunch) {
                tracker.trackEvent(
                    "billing_launch_activity_invalid_before_launch",
                    mapOf("resumed" to stillResumedBeforeLaunch, "destroyed" to stillDestroyedBeforeLaunch)
                )
                return BillingClient.BillingResponseCode.ERROR
            }

            BillingGuard.beginLaunch()

            val immediate = try {
                withContext(Dispatchers.Main) {
                    val launchStart = System.currentTimeMillis()
                    val stillResumedAtLaunch =
                        (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
                    val stillDestroyedAtLaunch = runCatching { activity.isDestroyed }.getOrDefault(false)
                    if (!stillResumedAtLaunch || activity.isFinishing || stillDestroyedAtLaunch) {
                        tracker.trackEvent(
                            "billing_launch_activity_invalid_at_launch",
                            mapOf("resumed" to stillResumedAtLaunch, "destroyed" to stillDestroyedAtLaunch)
                        )
                        throw IllegalStateException("Activity no longer valid for billing launch")
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
            tracker.trackEvent(
                "billing_launch_result",
                mapOf(
                    "rc" to rcName(immediate.responseCode),
                    "rc_code" to immediate.responseCode,
                    "msg" to (immediate.debugMessage ?: ""),
                    "error_category" to billingError.category.name,
                    "elapsed_ms" to (System.currentTimeMillis() - startTime),
                    "client_ready" to client.isReady
                )
            )

            if (immediate.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                getAdFree()
            }

            // 6) Retry once on transient unavailability
            if (immediate.responseCode == BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
                || immediate.responseCode == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
            ) {
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
                    tracker.trackError(e)
                    return immediate.responseCode
                }
                BillingGuard.beginLaunch()
                val retry = try {
                    withContext(Dispatchers.Main) {
                        val stillResumed2 =
                            (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
                        val stillDestroyed2 = runCatching { activity.isDestroyed }.getOrDefault(false)
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
                    tracker.trackError(e)
                    BillingGuard.endLaunch()
                    return BillingClient.BillingResponseCode.ERROR
                }
                tracker.trackEvent(
                    "billing_launch_retry_result", mapOf(
                        "rc" to rcName(retry.responseCode),
                        "rc_code" to retry.responseCode,
                        "msg" to (retry.debugMessage ?: "")
                    )
                )
                BillingGuard.endLaunch()
                return retry.responseCode
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
        BillingGuard.endLaunch()
        purchaseInProgress.set(false)

        tracker.trackEvent(
            "billing_updates_callback", mapOf(
                "rc" to rcName(result.responseCode),
                "msg" to result.debugMessage,
                "count" to (purchases?.size ?: 0)
            )
        )

        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases.isNullOrEmpty()) {
            if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                tracker.trackEvent("billing_updates_user_canceled")
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
                            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
                        client.acknowledgePurchase(params, cb)
                    }
                    tracker.trackEvent(
                        "billing_ack_attempt",
                        mapOf("purchaseToken" to token.take(12) + "…")
                    )
                    ackOnce { ackRes ->
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
                                    mapOf("rc" to rcName(ackRes.responseCode))
                                )
                                ackOnce { ackRes2 ->
                                    tracker.trackEvent(
                                        "billing_ack_result_retry", mapOf(
                                            "rc" to rcName(ackRes2.responseCode),
                                            "msg" to ackRes2.debugMessage
                                        )
                                    )
                                }
                            }

                            else -> tracker.trackEvent(
                                "billing_ack_result_terminal", mapOf(
                                    "rc" to rcName(ackRes.responseCode),
                                    "msg" to ackRes.debugMessage
                                )
                            )
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
    }

    // ---- internals ----

    private suspend fun ensureConnectedWithRetry() {
        val startTime = System.currentTimeMillis()
        var attempt = 1
        var lastError: Throwable? = null
        var lastDelay = INITIAL_RETRY_DELAY_MS

        while (attempt <= MAX_RETRY_ATTEMPTS) {
            coroutineContext.ensureActive()
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
        val finalError = lastError ?: IllegalStateException("Billing connect failed after $MAX_RETRY_ATTEMPTS attempts")
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
            existing.await()
            tracker.trackEvent(
                "billing_connect_wait_completed",
                mapOf("wait_ms" to (System.currentTimeMillis() - waitStart))
            )
            return
        }
        val created = CompletableDeferred<Unit>()
        if (!connectingRef.compareAndSet(null, created)) {
            connectingRef.get()!!.await()
            return
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
                            "debug_msg" to (r.debugMessage ?: "")
                        )
                    )
                    if (!created.isCompleted) {
                        created.complete(Unit)
                    } else {
                        tracker.trackEvent("billing_connect_callback_after_completion", mapOf("elapsed_ms" to elapsed))
                    }
                } else if (r.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) {
                    tracker.trackEvent(
                        "billing_connect_service_disconnected",
                        mapOf(
                            "rc" to rcName(r.responseCode),
                            "rc_code" to r.responseCode,
                            "msg" to (r.debugMessage ?: ""),
                            "error_category" to billingError.category.name,
                            "elapsed_ms" to elapsed
                        )
                    )
                    if (!created.isCompleted) {
                        created.completeExceptionally(
                            IllegalStateException("Billing connect failed: ${r.responseCode} ${r.debugMessage}")
                        )
                    } else {
                        tracker.trackEvent("billing_connect_callback_after_completion_disconnected", mapOf("elapsed_ms" to elapsed))
                    }
                } else {
                    tracker.trackEvent(
                        "billing_connect_fail",
                        mapOf(
                            "rc" to rcName(r.responseCode),
                            "rc_code" to r.responseCode,
                            "msg" to (r.debugMessage ?: ""),
                            "error_category" to billingError.category.name,
                            "elapsed_ms" to elapsed
                        )
                    )
                    if (!created.isCompleted) {
                        created.completeExceptionally(
                            IllegalStateException("Billing connect failed: ${r.responseCode} ${r.debugMessage}")
                        )
                    } else {
                        tracker.trackEvent("billing_connect_callback_after_completion_fail", mapOf("elapsed_ms" to elapsed))
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
        val result = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
        created.await()
        }
        if (result == null) {
            val currentRef = connectingRef.get()
            if (currentRef == created) {
                connectingRef.set(null)
                if (!created.isCompleted) {
                    created.cancel()
                }
            }
            tracker.trackEvent(
                "billing_connect_timeout",
                mapOf(
                    "timeout_ms" to CONNECTION_TIMEOUT_MS,
                    "elapsed_ms" to (System.currentTimeMillis() - connectStartTime),
                    "client_ready" to client.isReady
                )
            )
            throw IllegalStateException("Billing connection timeout after ${CONNECTION_TIMEOUT_MS}ms")
        }
    }

    private suspend fun getAdFree(): EntitlementState = suspendCancellableCoroutine { cont ->
        tracker.trackEvent("billing_query_purchases_start")
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { br, purchases ->
            if (!cont.isActive) return@queryPurchasesAsync
            if (br.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) {
                tracker.trackEvent("billing_query_purchases_need_reconnect")
                ioScope.launch {
                    runCatching { ensureConnectedWithRetry() }.onSuccess {
                        client.queryPurchasesAsync(params) { br2, p2 ->
                            tracker.trackEvent(
                                "billing_query_purchases_retry_result", mapOf(
                                    "rc" to rcName(br2.responseCode),
                                    "msg" to br2.debugMessage,
                                    "count" to p2.size
                                )
                            )
                            handlePurchasesResultAfterOk(br2, p2, cont)
                        }
                    }.onFailure { cont.safeResume(EntitlementState.UNKNOWN) }
                }
                return@queryPurchasesAsync
            }
            tracker.trackEvent(
                "billing_query_purchases_result", mapOf(
                    "rc" to rcName(br.responseCode),
                    "msg" to br.debugMessage,
                    "count" to purchases.size
                )
            )
            handlePurchasesResultAfterOk(br, purchases, cont)
        }
    }

    private suspend fun queryProductDetailsWithRetry(id: String): ProductDetails? {
        val startTime = System.currentTimeMillis()
        var attempt = 1
        var lastDelay = INITIAL_RETRY_DELAY_MS

        while (attempt <= MAX_RETRY_ATTEMPTS) {
            coroutineContext.ensureActive()
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

            val result = queryProductDetails(id, attempt, startTime)
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
        startTime: Long
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
                    "msg" to (br.debugMessage ?: ""),
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
                        "price" to (match.oneTimePurchaseOfferDetails?.formattedPrice ?: "n/a"),
                        "has_one_time" to (match.oneTimePurchaseOfferDetails != null),
                        "attempt" to attempt,
                        "elapsed_ms" to elapsed
                    )
                )
            }
                cont.safeResume(match)
            }
        }

    private fun handlePurchasesResultAfterOk(
        br: BillingResult,
        purchases: List<Purchase>,
        cont: CancellableContinuation<EntitlementState>,
    ) {
        if (br.responseCode != BillingClient.BillingResponseCode.OK) {
            cont.safeResume(EntitlementState.UNKNOWN); return
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
                    mapOf("token" to it.purchaseToken.take(12) + "…")
                )
                client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder().setPurchaseToken(it.purchaseToken)
                        .build()
                ) { ackRes ->
                    tracker.trackEvent(
                        "billing_ack_result_on_query",
                        mapOf("rc" to rcName(ackRes.responseCode))
                    )
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
}
