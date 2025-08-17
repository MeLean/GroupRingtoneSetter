package com.milen.grounpringtonesetter.utils

import android.os.Build
import android.os.Bundle
import android.os.Parcelable

inline fun <reified T : Parcelable> Bundle.parcelableOrThrow(key: String): T {
    val value: T? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, T::class.java)
    } else {
        @Suppress("DEPRECATION") getParcelable(key)
    }
    return requireNotNull(value) {
        "Missing required Parcelable for key=\"$key\" of type ${T::class.java.name}"
    }
}

inline fun <reified T : Parcelable> Bundle.parcelableArrayListOrThrow(key: String): ArrayList<T> {
    val value: ArrayList<T>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayList(key, T::class.java)
    } else {
        @Suppress("DEPRECATION") getParcelableArrayList(key)
    }
    return requireNotNull(value) {
        "Missing required Parcelable ArrayList for key=\"$key\" of type ${T::class.java.name}"
    }
}