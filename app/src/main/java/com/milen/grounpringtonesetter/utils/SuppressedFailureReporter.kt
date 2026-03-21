package com.milen.grounpringtonesetter.utils

import android.content.Context
import com.milen.grounpringtonesetter.App

internal fun Context?.trackSuppressedFailure(
    source: String,
    throwable: Throwable,
) {
    val tracker = (this?.applicationContext as? App)?.tracker ?: return
    tracker.trackError(
        SuppressedFailureException(
            source = source,
            cause = throwable
        )
    )
}

internal class SuppressedFailureException(
    val source: String,
    cause: Throwable,
) : IllegalStateException(
    buildString {
        append("Suppressed failure at ")
        append(source)
        append(": ")
        append(cause::class.java.simpleName)
        cause.message?.takeIf { it.isNotBlank() }?.let { message ->
            append(" - ").append(message)
        }
    },
    cause
)
