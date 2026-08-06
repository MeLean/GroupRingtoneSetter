package com.milen.grounpringtonesetter.data.exceptions

internal fun Throwable.isContactsPermissionFailure(): Boolean =
    generateSequence(this) { it.cause }
        .any { cause -> cause is SecurityException }
