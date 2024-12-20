package com.milen.grounpringtonesetter.utils

import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase

class Tracker {

    fun trackEvent(eventName: String, params: Map<String, Any>? = null) {
        Firebase.analytics.logEvent(eventName, params.toBundle())
    }

    fun trackError(error: Throwable) {
        Firebase.crashlytics.recordException(error)
    }
}

private fun Map<String, Any>?.toBundle(): Bundle? = this?.let {
    bundleOf(*it.toList().toTypedArray())
}
