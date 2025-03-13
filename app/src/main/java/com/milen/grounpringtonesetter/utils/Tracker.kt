package com.milen.grounpringtonesetter.utils

import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase

class Tracker {

    fun trackEvent(eventName: String, params: Map<String, Any>? = null) {
        // Log.d("TEST_IT", "Event logged: $eventName, Params: ${params.orEmpty()}")
        Firebase.analytics.logEvent(eventName, params.toBundle())
    }

    fun trackError(error: Throwable) {
        // Log.e("TEST_IT", "Error logged: ${error.message}", error)
        Firebase.crashlytics.recordException(error)
    }
}

private fun Map<String, Any>?.toBundle(): Bundle? = this?.let {
    bundleOf(*it.toList().toTypedArray())
}
