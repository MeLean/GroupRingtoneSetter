package com.milen.grounpringtonesetter

import android.os.Build

internal fun webViewDataDirectorySuffix(sdkInt: Int): String? =
    if (sdkInt >= Build.VERSION_CODES.P) "main" else null
