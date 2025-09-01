package com.milen.grounpringtonesetter.billing

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class BillingEntitlementManager(
    app: Application,
    private val tracker: Tracker,
) : PurchasesUpdatedListener {

    private val productId = "remove_ads_forever"

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(EntitlementState.UNKNOWN)
    val state: kotlinx.coroutines.flow.StateFlow<EntitlementState> = _state

    private val grace = AdFreeGraceStore(app)

    @Volatile
    private var purchaseInProgress = false

    private val client: BillingClient = BillingClient.newBuilder(app)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .setListener(this)
        .build()

    /** Call at startup from a coroutine. Leaves UNKNOWN on failure. */
    suspend fun start() = runCatching {
        tracker.trackEvent("billing_start_called")
        ensureConnectedWithRetry()
        getAdFree()
    }.onSuccess {
        tracker.trackEvent("billing_start_ok", mapOf("state" to _state.value.name))
    }.onFailure { e ->
        if (e is CancellationException) throw e
        tracker.trackError(e)
    }.getOrNull()

    suspend fun launchPurchase(activity: Activity): Int {
        tracker.trackEvent("billing_launch_called")

        ensureConnectedWithRetry()

        val resumed = (activity as? LifecycleOwner)?.lifecycle
            ?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        val destroyed = try {
            activity.isDestroyed
        } catch (_: Throwable) {
            false
        }

        tracker.trackEvent(
            "billing_prelaunch_check",
            mapOf(
                "in_progress" to purchaseInProgress,
                "resumed" to resumed,
                "finishing" to activity.isFinishing,
                "destroyed" to destroyed
            )
        )

        if (!resumed || activity.isFinishing || destroyed) {
            tracker.trackEvent(
                "billing_abort_activity_state",
                mapOf(
                    "resumed" to resumed,
                    "finishing" to activity.isFinishing,
                    "destroyed" to destroyed
                )
            )
            tracker.trackError(RuntimeException("Activity not in a valid state for billing launch"))
            return BillingClient.BillingResponseCode.ERROR
        }

        if (purchaseInProgress) {
            tracker.trackError(RuntimeException("Purchase already in progress"))
            return BillingClient.BillingResponseCode.DEVELOPER_ERROR
        }
        purchaseInProgress = true

        try {
            val pd = queryProductDetails(productId)
            if (pd == null) {
                tracker.trackEvent("billing_abort_pd_null", mapOf("id" to productId))
                tracker.trackError(RuntimeException("ProductDetails null"))
                return BillingClient.BillingResponseCode.ITEM_UNAVAILABLE
            }

            if (!pd.isUsableInapp()) {
                tracker.trackEvent("billing_abort_pd_not_sellable", mapOf("id" to pd.productId))
                tracker.trackError(RuntimeException("ProductDetails not sellable (no one-time offer)"))
                return BillingClient.BillingResponseCode.ITEM_UNAVAILABLE
            }

            tracker.trackEvent(
                "billing_launch_attempt",
                mapOf(
                    "id" to pd.productId,
                    "title" to pd.title,
                    "price" to (pd.oneTimePurchaseOfferDetails?.formattedPrice ?: "n/a")
                )
            )

            val flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(pd)
                            .build()
                    )
                )
                .build()

            val immediate = withContext(Dispatchers.Main) {
                client.launchBillingFlow(activity, flow)
            }
            tracker.trackEvent(
                "billing_launch_result",
                mapOf("rc" to rcName(immediate.responseCode), "msg" to immediate.debugMessage)
            )

            // Handle common real-world outcome
            if (immediate.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                tracker.trackEvent("billing_item_already_owned_autogrant_start")
                // Reconcile immediately
                getAdFree()
                tracker.trackEvent(
                    "billing_item_already_owned_autogrant_done",
                    mapOf("state" to _state.value.name)
                )
            }

            // One-shot retry for transient launch errors
            if (immediate.responseCode == BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE ||
                immediate.responseCode == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
            ) {
                tracker.trackEvent(
                    "billing_launch_retry_due_transient",
                    mapOf("rc" to rcName(immediate.responseCode))
                )
                delay(400)
                ensureConnectedWithRetry()
                val retry = withContext(Dispatchers.Main) {
                    client.launchBillingFlow(activity, flow)
                }
                tracker.trackEvent(
                    "billing_launch_retry_result",
                    mapOf("rc" to rcName(retry.responseCode), "msg" to retry.debugMessage)
                )
                return retry.responseCode
            }

            return immediate.responseCode
        } catch (t: Throwable) {
            tracker.trackEvent(
                "billing_launch_exception",
                mapOf("error" to (t.message ?: "unknown"))
            )
            tracker.trackError(t)
            throw t
        } finally {
            purchaseInProgress = false
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        purchaseInProgress = false
        tracker.trackEvent(
            "billing_updates_callback",
            mapOf(
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

        // Separate PENDING vs PURCHASED for clarity
        val hasPending = purchases.any {
            it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PENDING
        }
        if (hasPending) {
            tracker.trackEvent("billing_purchase_pending")
        }

        val owns = purchases.any {
            it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        tracker.trackEvent("billing_updates_has_owned", mapOf("owns" to owns))
        if (!owns) return

        // Acknowledge with 1 retry for transient failures
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
                    if (ackRes.responseCode == BillingClient.BillingResponseCode.OK) {
                        tracker.trackEvent("billing_ack_result", mapOf("rc" to "OK"))
                    } else if (ackRes.responseCode == BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE ||
                        ackRes.responseCode == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
                    ) {
                        tracker.trackEvent(
                            "billing_ack_retry_due_transient",
                            mapOf("rc" to rcName(ackRes.responseCode))
                        )
                        // Retry once
                        ackOnce { ackRes2 ->
                            tracker.trackEvent(
                                "billing_ack_result_retry",
                                mapOf(
                                    "rc" to rcName(ackRes2.responseCode),
                                    "msg" to ackRes2.debugMessage
                                )
                            )
                        }
                    } else {
                        tracker.trackEvent(
                            "billing_ack_result_terminal",
                            mapOf("rc" to rcName(ackRes.responseCode), "msg" to ackRes.debugMessage)
                        )
                    }
                }
            }

        _state.value = EntitlementState.OWNED
        val until = System.currentTimeMillis() + AdFreeGraceStore.GRACE_TTL_MILLIS
        grace.saveAdFreeUntil(until)
        tracker.trackEvent("billing_entitlement_owned", mapOf("grace_until" to until))
    }

    // ---- internals ----

    private suspend fun ensureConnectedWithRetry() {
        val maxAttempts = 2
        var attempt = 1
        var lastError: Throwable? = null
        while (attempt <= maxAttempts) {
            try {
                ensureConnected()
                if (attempt > 1) tracker.trackEvent(
                    "billing_connect_retry_success",
                    mapOf("attempt" to attempt)
                )
                return
            } catch (t: Throwable) {
                lastError = t
                tracker.trackEvent(
                    "billing_connect_attempt_fail",
                    mapOf("attempt" to attempt, "error" to (t.message ?: "unknown"))
                )
                if (attempt == maxAttempts) break
                delay(300)
                attempt++
            }
        }
        throw lastError ?: IllegalStateException("Billing connect failed")
    }

    private suspend fun ensureConnected(): Unit = suspendCancellableCoroutine { cont ->
        if (client.isReady) {
            tracker.trackEvent("billing_connect_already_ready")
            cont.safeResume(Unit); return@suspendCancellableCoroutine
        }

        tracker.trackEvent("billing_connect_start")
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (!cont.isActive) return
                if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                    tracker.trackEvent("billing_connect_ok")
                    cont.safeResume(Unit)
                } else {
                    tracker.trackEvent(
                        "billing_connect_fail",
                        mapOf("rc" to rcName(r.responseCode), "msg" to r.debugMessage)
                    )
                    cont.resumeWithException(
                        IllegalStateException("Billing connect failed: ${r.responseCode} ${r.debugMessage}")
                    )
                }
            }

            override fun onBillingServiceDisconnected() {
                tracker.trackEvent("billing_connect_disconnected")
            }
        })

        cont.invokeOnCancellation { tracker.trackEvent("billing_connect_cancelled") }
    }

    private suspend fun getAdFree(): EntitlementState = suspendCancellableCoroutine { cont ->
        tracker.trackEvent("billing_query_purchases_start")

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        client.queryPurchasesAsync(params) { br, purchases ->
            if (!cont.isActive) return@queryPurchasesAsync
            tracker.trackEvent(
                "billing_query_purchases_result",
                mapOf(
                    "rc" to rcName(br.responseCode),
                    "msg" to br.debugMessage,
                    "count" to purchases.size
                )
            )

            if (br.responseCode != BillingClient.BillingResponseCode.OK) {
                cont.safeResume(EntitlementState.UNKNOWN); return@queryPurchasesAsync
            }

            val owns = purchases.any {
                it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            val hasPending = purchases.any {
                it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PENDING
            }
            if (hasPending) tracker.trackEvent("billing_query_purchases_pending")

            if (owns) {
                purchases.filter { it.products.contains(productId) && !it.isAcknowledged }
                    .forEach {
                        tracker.trackEvent(
                            "billing_ack_attempt_on_query",
                            mapOf("token" to it.purchaseToken.take(12) + "…")
                        )
                        client.acknowledgePurchase(
                            AcknowledgePurchaseParams.newBuilder()
                                .setPurchaseToken(it.purchaseToken).build()
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
                tracker.trackEvent(
                    "billing_entitlement_owned_on_query",
                    mapOf("grace_until" to until)
                )
            } else {
                grace.clear()
                _state.value = EntitlementState.NOT_OWNED
                tracker.trackEvent("billing_entitlement_not_owned_on_query")
            }

            cont.safeResume(_state.value)
        }
    }

    private suspend fun queryProductDetails(id: String): ProductDetails? =
        suspendCancellableCoroutine { cont ->
            tracker.trackEvent("billing_pd_query_start", mapOf("id" to id))

            val q = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(id)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    )
                )
                .build()

            client.queryProductDetailsAsync(q) { br, result ->
                if (!cont.isActive) return@queryProductDetailsAsync
                val list = result.productDetailsList
                tracker.trackEvent(
                    "billing_pd_query_result",
                    mapOf(
                        "rc" to rcName(br.responseCode),
                        "msg" to br.debugMessage,
                        "count" to list.size,
                        "ids" to list.joinToString { it.productId }
                    )
                )

                if (br.responseCode != BillingClient.BillingResponseCode.OK) {
                    cont.safeResume(null); return@queryProductDetailsAsync
                }

                val match = list.firstOrNull { it.productId == id }
                if (match == null) {
                    tracker.trackEvent("billing_pd_match_null", mapOf("id" to id))
                } else {
                    tracker.trackEvent(
                        "billing_pd_match_found",
                        mapOf(
                            "id" to match.productId,
                            "title" to match.title,
                            "price" to (match.oneTimePurchaseOfferDetails?.formattedPrice ?: "n/a")
                        )
                    )
                }
                cont.safeResume(match)
            }
        }

    // Helpers
    private fun <T> CancellableContinuation<T>.safeResume(value: T) {
        if (isActive) resume(value)
    }

    private fun ProductDetails.isUsableInapp(): Boolean {
        return try {
            this.oneTimePurchaseOfferDetails != null ||
                    (ProductDetails::class.java.getMethod("getOneTimePurchaseOfferDetailsList")
                        .invoke(this) as? List<*>)?.isNotEmpty() == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun rcName(code: Int): String = when (code) {
        BillingClient.BillingResponseCode.OK -> "OK"
        BillingClient.BillingResponseCode.USER_CANCELED -> "USER_CANCELED"
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE"
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "ITEM_UNAVAILABLE"
        BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
        BillingClient.BillingResponseCode.ERROR -> "ERROR"
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED"
        BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> "ITEM_NOT_OWNED"
        else -> "UNKNOWN_$code"
    }
}
