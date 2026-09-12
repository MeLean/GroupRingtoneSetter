package com.milen.grounpringtonesetter.utils

import android.os.Build
import android.os.Bundle
import android.os.Parcelable

inline fun <reified T : Parcelable> Bundle.parcelableOrThrow(key: String): T {
    val value: T? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, T::class.java)
        } else {
            @Suppress("DEPRECATION") getParcelable(key)
        }
    } catch (_: Exception) {
        @Suppress("DEPRECATION") getParcelable(key)
    }
    return requireNotNull(value) {
        "Missing required Parcelable for key=\"$key\" of type ${T::class.java.name}"
    }
}

inline fun <reified T : Parcelable> Bundle.parcelableArrayListOrThrow(key: String): ArrayList<T> {
    val value: ArrayList<T>? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayList(key, T::class.java)
        } else {
            @Suppress("DEPRECATION") getParcelableArrayList(key)
        }
    } catch (_: Exception) {
        @Suppress("DEPRECATION") getParcelableArrayList(key)
    }
    return requireNotNull(value) {
        "Missing required Parcelable ArrayList for key=\"$key\" of type ${T::class.java.name}"
    }
}

inline fun <reified T : Parcelable> Bundle.parcelableOrNull(key: String): T? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, T::class.java)
        } else {
            @Suppress("DEPRECATION") getParcelable(key) as? T
        }
    } catch (_: Exception) {
        @Suppress("DEPRECATION") getParcelable(key) as? T
    }
}

inline fun <reified T : Parcelable> Bundle.parcelableArrayListOrEmpty(key: String): ArrayList<T> {
    val result = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayList(key, T::class.java)
        } else {
            @Suppress("DEPRECATION") getParcelableArrayList(key)
        }
    } catch (_: Exception) {
        @Suppress("DEPRECATION") getParcelableArrayList(key)
    }

    return result ?: arrayListOf()
}
