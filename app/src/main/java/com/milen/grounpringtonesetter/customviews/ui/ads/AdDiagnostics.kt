package com.milen.grounpringtonesetter.customviews.ui.ads

import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.ResponseInfo
import com.milen.grounpringtonesetter.BuildConfig
import com.milen.grounpringtonesetter.utils.Telemetry
import com.milen.grounpringtonesetter.utils.log

internal object AdDiagnostics {

    fun logDebugEvent(
        format: String,
        placement: String,
        message: String,
    ) {
        if (!BuildConfig.DEBUG) return
        "CODEX_DEBUG [$format][$placement] $message".log()
    }

    fun logLoadFailure(
        format: String,
        placement: String,
        adUnitId: String,
        error: LoadAdError,
    ) {
        if (!BuildConfig.DEBUG) return
        val responseInfo = error.responseInfo
        logDebugEvent(
            format = format,
            placement = placement,
            message = buildString {
                append("load failed")
                append(" unit=").append(adUnitId)
                append(" code=").append(error.code)
                append(" domain=").append(error.domain)
                append(" message=").append(error.message)
                append(" cause=").append(error.cause?.message ?: "null")
                append(" responseId=").append(responseInfo?.responseId ?: "null")
                append(" adapter=").append(responseInfo?.mediationAdapterClassName ?: "null")
            }
        )
    }

    fun logShowFailure(
        format: String,
        placement: String,
        adUnitId: String,
        error: AdError,
    ) {
        if (!BuildConfig.DEBUG) return
        logDebugEvent(
            format = format,
            placement = placement,
            message = buildString {
                append("show failed")
                append(" unit=").append(adUnitId)
                append(" code=").append(error.code)
                append(" domain=").append(error.domain)
                append(" message=").append(error.message)
                append(" cause=").append(error.cause?.message ?: "null")
            }
        )
    }

    fun trackPaidEvent(
        tracker: Telemetry,
        format: String,
        placement: String,
        adUnitId: String,
        adValue: AdValue,
        responseInfo: ResponseInfo?,
    ) {
        tracker.trackEvent(
            "ad_paid_event",
            mapOf(
                "ad_format" to format,
                "placement" to placement,
                "ad_unit_id" to adUnitId,
                "value_micros" to adValue.valueMicros,
                "currency_code" to adValue.currencyCode,
                "precision_type" to adValue.precisionType,
                "response_id" to (responseInfo?.responseId ?: "unknown"),
                "mediation_adapter" to (responseInfo?.mediationAdapterClassName ?: "unknown")
            )
        )
    }

    fun trackUnexpectedState(
        tracker: Telemetry,
        format: String,
        placement: String,
        stage: String,
        reason: String,
        throwable: Throwable? = null,
    ) {
        tracker.trackEvent(
            "ad_unexpected_state",
            mapOf(
                "ad_format" to format,
                "placement" to placement,
                "stage" to stage,
                "reason" to reason,
                "throwable_type" to (throwable?.javaClass?.simpleName ?: "none"),
                "throwable_message" to (throwable?.message ?: "none")
            )
        )
        logDebugEvent(
            format = format,
            placement = placement,
            message = "unexpected stage=$stage reason=$reason throwable=${throwable?.javaClass?.simpleName ?: "none"} message=${throwable?.message ?: "none"}"
        )
        tracker.trackError(
            IllegalStateException(
                "Unexpected ad state format=$format placement=$placement stage=$stage reason=$reason",
                throwable
            )
        )
    }
}
