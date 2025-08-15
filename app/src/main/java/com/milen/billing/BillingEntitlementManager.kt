package com.milen.billing

import android.app.Activity
import android.app.Application
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
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class BillingEntitlementManager(app: Application) : PurchasesUpdatedListener {

    private val productId = "remove_ads_forever"

    private val _state = MutableStateFlow(EntitlementState.UNKNOWN)
    val state: StateFlow<EntitlementState> = _state

    private val grace = AdFreeGraceStore(app)

    private val client: BillingClient = BillingClient.newBuilder(app)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .setListener(this)
        .build()

    /** Call from a coroutine (e.g., lifecycleScope) at startup. Leaves UNKNOWN on failure. */
    suspend fun start() = runCatching {
        ensureConnected()
        getAdFree()
    }.recoverCatching { e ->
        if (e is CancellationException) throw e
    }.getOrNull()

    /**
     * Launch purchase flow for [productId].
     * Suspends only until the flow is launched; returns the immediate responseCode.
     * Purchase outcome (success/cancel/pending) arrives via onPurchasesUpdated(...).
     */
    suspend fun launchPurchase(activity: Activity): Int {
        ensureConnected()

        val pd = queryProductDetails(productId)
            ?: return BillingClient.BillingResponseCode.ITEM_UNAVAILABLE

        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(pd)
                        .build()
                )
            )
            .build()

        val launch = client.launchBillingFlow(activity, flow)
        return launch.responseCode
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases.isNullOrEmpty()) return

        val owns =
            purchases.any { it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (!owns) return

        purchases.filter { it.products.contains(productId) && !it.isAcknowledged }.forEach {
            val params =
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(it.purchaseToken).build()
            client.acknowledgePurchase(params) {/* ignore */ }
        }

        // Flip state & extend grace immediately
        _state.value = EntitlementState.OWNED
        val until = System.currentTimeMillis() + AdFreeGraceStore.GRACE_TTL_MILLIS
        grace.saveAdFreeUntil(until)
    }

    private suspend fun ensureConnected(): Unit = suspendCancellableCoroutine { cont ->
        if (client.isReady) {
            cont.safeResume(Unit); return@suspendCancellableCoroutine
        }

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (!cont.isActive) return
                if (r.responseCode == BillingClient.BillingResponseCode.OK) cont.safeResume(Unit)
                else cont.resumeWithException(IllegalStateException("Billing connect failed: ${r.responseCode} ${r.debugMessage}"))
            }

            override fun onBillingServiceDisconnected() {
                // Auto reconnection is enabled; next API call will reconnect.
                // If connect is still pending, we'll rely on onBillingSetupFinished above.
            }
        })

        cont.invokeOnCancellation {
            // If you hard-cancel during connect and want to release: client.endConnection()
        }
    }

    private suspend fun getAdFree(): EntitlementState = suspendCancellableCoroutine { cont ->
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        client.queryPurchasesAsync(params) { br, purchases ->
            if (!cont.isActive) return@queryPurchasesAsync

            if (br.responseCode != BillingClient.BillingResponseCode.OK) {
                cont.safeResume(EntitlementState.UNKNOWN); return@queryPurchasesAsync
            }

            val owns =
                purchases.any { it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PURCHASED }

            if (owns) {
                purchases.filter { it.products.contains(productId) && !it.isAcknowledged }.forEach {
                    client.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(it.purchaseToken)
                            .build()
                    ) {/* ignore */ }
                }
                val until = System.currentTimeMillis() + AdFreeGraceStore.GRACE_TTL_MILLIS
                grace.saveAdFreeUntil(until)
                _state.value = EntitlementState.OWNED
            } else {
                grace.clear()
                _state.value = EntitlementState.NOT_OWNED
            }

            cont.safeResume(_state.value)
        }
    }

    private suspend fun queryProductDetails(id: String): ProductDetails? =
        suspendCancellableCoroutine { cont ->
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

                if (br.responseCode != BillingClient.BillingResponseCode.OK) {
                    cont.safeResume(null); return@queryProductDetailsAsync
                }

                cont.safeResume(result.productDetailsList.firstOrNull())
            }
        }

    // Helper to avoid IllegalStateException when resuming after cancellation.
    private fun <T> CancellableContinuation<T>.safeResume(value: T) {
        if (isActive) resume(value)
    }
}