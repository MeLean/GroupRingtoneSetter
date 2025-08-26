package com.milen.grounpringtonesetter.utils

import android.util.Log
import com.milen.grounpringtonesetter.BuildConfig

private const val TAG = "TEST_IT"

internal fun String.log() {
    if (BuildConfig.DEBUG) {
        Log.d(TAG, this)
    }
}