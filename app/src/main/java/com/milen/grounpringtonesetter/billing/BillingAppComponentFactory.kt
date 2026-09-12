package com.milen.grounpringtonesetter.billing

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.ResultReceiver
import android.util.Log
import androidx.core.app.AppComponentFactory

internal const val PROXY_BILLING_ACTIVITY_CLASS =
    "com.android.billingclient.api.ProxyBillingActivity"

class BillingAppComponentFactory : AppComponentFactory() {
    override fun instantiateActivityCompat(
        classLoader: ClassLoader,
        className: String,
        intent: Intent?,
    ): Activity {
        if (className != PROXY_BILLING_ACTIVITY_CLASS) {
            return super.instantiateActivityCompat(classLoader, className, intent)
        }

        val compatible = BillingPlatformCapabilities.isCompatible()
        val intentStatus = BillingProxyIntentValidator.inspect(intent)
        val blockReason = when {
            !compatible -> BillingProxyBlockReason.PLATFORM_INCOMPATIBLE
            intentStatus != BillingProxyIntentStatus.VALID ->
                BillingProxyBlockReason.MALFORMED_INTENT
            else -> null
        }

        Log.i(
            TAG,
            "activity=$className compatible=$compatible decision=" +
                (blockReason?.logValue ?: "allow_valid_billing_contract"),
        )

        return if (blockReason == null) {
            super.instantiateActivityCompat(classLoader, className, intent)
        } else {
            BillingUnavailableActivity().apply {
                configure(blockReason, compatible, intentStatus)
            }
        }
    }

    private companion object {
        const val TAG = "BillingProxyGuard"
    }
}

internal enum class BillingProxyIntentStatus {
    VALID,
    MISSING,
    INVALID_TYPE,
    UNREADABLE,
}

internal enum class BillingProxyBlockReason(val logValue: String) {
    PLATFORM_INCOMPATIBLE("block_platform_incompatible"),
    MALFORMED_INTENT("block_malformed_intent"),
}

internal object BillingProxyIntentValidator {
    private const val BUY_INTENT = "BUY_INTENT"
    private const val IN_APP_MESSAGE_INTENT = "IN_APP_MESSAGE_INTENT"
    private const val IN_APP_MESSAGE_RESULT_RECEIVER = "in_app_message_result_receiver"

    fun inspect(intent: Intent?): BillingProxyIntentStatus = try {
        when {
            intent == null -> BillingProxyIntentStatus.MISSING
            intent.hasExtra(BUY_INTENT) ->
                if (intent.parcelable(BUY_INTENT, PendingIntent::class.java)?.intentSender != null) {
                    BillingProxyIntentStatus.VALID
                } else {
                    BillingProxyIntentStatus.INVALID_TYPE
                }
            intent.hasExtra(IN_APP_MESSAGE_INTENT) -> {
                val pendingIntent = intent.parcelable(
                    IN_APP_MESSAGE_INTENT,
                    PendingIntent::class.java,
                )
                val receiver = intent.parcelable(
                    IN_APP_MESSAGE_RESULT_RECEIVER,
                    ResultReceiver::class.java,
                )
                if (pendingIntent?.intentSender != null && receiver != null) {
                    BillingProxyIntentStatus.VALID
                } else {
                    BillingProxyIntentStatus.INVALID_TYPE
                }
            }
            else -> BillingProxyIntentStatus.MISSING
        }
    } catch (_: Throwable) {
        BillingProxyIntentStatus.UNREADABLE
    }

    private fun <T> Intent.parcelable(key: String, type: Class<T>): T? where T : android.os.Parcelable =
        extras?.get(key)?.takeIf(type::isInstance)?.let(type::cast)
}
