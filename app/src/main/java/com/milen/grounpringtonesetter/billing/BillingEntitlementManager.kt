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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

    private val client: BillingClient = BillingClient.newBuilder(app)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .setListener(this)
        .build()

    private val connectingRef = AtomicReference<CompletableDeferred<Unit>?>(null)

    fun end() {
        runCatching { client.endConnection() }
    }

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

        try {
            ensureConnectedWithRetry()
        } catch (e: Exception) {
            tracker.trackError(e)
            return BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
        }

        val resumed =
            (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        val destroyed = try {
            activity.isDestroyed
        } catch (_: Throwable) {
            false
        }

        tracker.trackEvent(
            "billing_prelaunch_check", mapOf(
                "in_progress" to purchaseInProgress.get(),
                "resumed" to resumed,
                "finishing" to activity.isFinishing,
                "destroyed" to destroyed
            )
        )

        if (!resumed || activity.isFinishing || destroyed) {
            tracker.trackError(RuntimeException("Activity not in valid state for billing launch"))
            return BillingClient.BillingResponseCode.ERROR
        }

        if (!purchaseInProgress.compareAndSet(false, true)) {
            tracker.trackError(RuntimeException("Purchase already in progress"))
            return BillingClient.BillingResponseCode.DEVELOPER_ERROR
        }

        try {
            val pd = queryProductDetails(productId)
            if (pd == null) {
                tracker.trackError(RuntimeException("ProductDetails null"))
                return BillingClient.BillingResponseCode.ITEM_UNAVAILABLE
            }
            if (!pd.isUsableInapp()) {
                tracker.trackError(RuntimeException("ProductDetails not sellable"))
                return BillingClient.BillingResponseCode.ITEM_UNAVAILABLE
            }

            val flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(pd)
                            .build()
                    )
                ).build()

            BillingGuard.beginLaunch()

            val immediate = try {
                withContext(Dispatchers.Main) {
                    client.launchBillingFlow(activity, flow)
                }
            } catch (e: Exception) {
                tracker.trackError(e)
                BillingGuard.endLaunch()
                return BillingClient.BillingResponseCode.ERROR
            }

            tracker.trackEvent(
                "billing_launch_result", mapOf(
                    "rc" to rcName(immediate.responseCode),
                    "msg" to immediate.debugMessage
                )
            )

            if (immediate.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                getAdFree()
            }

            if (immediate.responseCode == BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE ||
                immediate.responseCode == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
            ) {
                delay(400)
                try {
                    ensureConnectedWithRetry()
                } catch (e: Exception) {
                    tracker.trackError(e)
                    return immediate.responseCode
                }

                BillingGuard.beginLaunch()
                val retry = try {
                    withContext(Dispatchers.Main) {
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
                        "msg" to retry.debugMessage
                    )
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
            BillingGuard.endLaunch()
            purchaseInProgress.set(false)
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

        val hasPending =
            purchases.any { it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PENDING }
        if (hasPending) tracker.trackEvent("billing_purchase_pending")

        val owns =
            purchases.any { it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        tracker.trackEvent("billing_updates_has_owned", mapOf("owns" to owns))
        if (!owns) return

        purchases.filter { it.products.contains(productId) && !it.isAcknowledged }.forEach { p ->
            val token = p.purchaseToken
            fun ackOnce(cb: (BillingResult) -> Unit) {
                val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
                client.acknowledgePurchase(params, cb)
            }
            tracker.trackEvent(
                "billing_ack_attempt",
                mapOf("purchaseToken" to token.take(12) + "…")
            )
            ackOnce { ackRes ->
                when (ackRes.responseCode) {
                    BillingClient.BillingResponseCode.OK -> {
                        tracker.trackEvent("billing_ack_result", mapOf("rc" to "OK"))
                    }
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

                    else -> {
                        tracker.trackEvent(
                            "billing_ack_result_terminal", mapOf(
                                "rc" to rcName(ackRes.responseCode),
                                "msg" to ackRes.debugMessage
                            )
                        )
                    }
                }
            }
        }

        _state.value = EntitlementState.OWNED
        val until = System.currentTimeMillis() + AdFreeGraceStore.GRACE_TTL_MILLIS
        grace.saveAdFreeUntil(until)
        tracker.trackEvent("billing_entitlement_owned", mapOf("grace_until" to until))
    }

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

    private suspend fun ensureConnected() {
        if (client.isReady) {
            tracker.trackEvent("billing_connect_already_ready")
            return
        }

        connectingRef.get()?.let { existing ->
            tracker.trackEvent("billing_connect_wait_existing")
            existing.await()
            return
        }

        val created = CompletableDeferred<Unit>()
        if (!connectingRef.compareAndSet(null, created)) {
            connectingRef.get()!!.await()
            return
        }

        tracker.trackEvent("billing_connect_start")
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                connectingRef.set(null)
                if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                    tracker.trackEvent("billing_connect_ok")
                    created.complete(Unit)
                } else {
                    tracker.trackEvent(
                        "billing_connect_fail",
                        mapOf("rc" to rcName(r.responseCode), "msg" to r.debugMessage)
                    )
                    created.completeExceptionally(IllegalStateException("Billing connect failed: ${r.responseCode} ${r.debugMessage}"))
                }
            }

            override fun onBillingServiceDisconnected() {
                tracker.trackEvent("billing_connect_disconnected")
            }
        })

        created.await()
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
                    runCatching { ensureConnectedWithRetry() }
                        .onSuccess {
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
                        }
                        .onFailure {
                            cont.safeResume(EntitlementState.UNKNOWN)
                        }
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
                ).build()

            client.queryProductDetailsAsync(q) { br, result ->
                if (!cont.isActive) return@queryProductDetailsAsync
                val list = result.productDetailsList
                tracker.trackEvent(
                    "billing_pd_query_result", mapOf(
                        "rc" to rcName(br.responseCode),
                        "msg" to br.debugMessage,
                        "count" to list.size,
                        "ids" to list.joinToString { it.productId }
                    ))

                if (br.responseCode != BillingClient.BillingResponseCode.OK) {
                    cont.safeResume(null); return@queryProductDetailsAsync
                }

                val match = list.firstOrNull { it.productId == id }
                if (match == null) {
                    tracker.trackEvent("billing_pd_match_null", mapOf("id" to id))
                } else {
                    tracker.trackEvent(
                        "billing_pd_match_found", mapOf(
                            "id" to match.productId,
                            "title" to match.title,
                            "price" to (match.oneTimePurchaseOfferDetails?.formattedPrice ?: "n/a")
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
        val hasPending =
            purchases.any { it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PENDING }
        if (hasPending) tracker.trackEvent("billing_query_purchases_pending")

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
            _state.value = EntitlementState.NOT_OWNED
            tracker.trackEvent("billing_entitlement_not_owned_on_query")
        }

        cont.safeResume(_state.value)
    }

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
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
        else -> "UNKNOWN_$code"
    }
}