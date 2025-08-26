package com.milen.grounpringtonesetter.utils

import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics


internal class Tracker {

    fun trackEvent(eventName: String, params: Map<String, Any>? = null) {
        "$eventName: ${params?.toString().orEmpty()}".log()

        try {
            // Analytics event
            Firebase.analytics.logEvent(eventName, params.toBundle())
        } catch (_: Throwable) { /* ignore */
        }

        try {
            // Breadcrumb in Crashlytics
            Firebase.crashlytics.log("event:$eventName ${params?.entries?.joinToString()}")
            // Store keys for context
            params?.forEach { (k, v) ->
                Firebase.crashlytics.setCustomKey(k, v.toString())
            }
        } catch (_: Throwable) { /* ignore */
        }
    }

    fun trackError(error: Throwable) {
        "Error: ${error.message}".log()

        try {
            // Record non-fatal in Crashlytics
            Firebase.crashlytics.recordException(error)
        } catch (_: Throwable) { /* ignore */
        }
    }
}

private fun Map<String, Any>?.toBundle(): Bundle? = this?.let {
    val safePairs = it.map { (k, v) ->
        when (v) {
            is String, is Number, is Boolean -> k to v
            else -> k to v.toString()
        }
    }.toTypedArray()
    bundleOf(*safePairs)
}
