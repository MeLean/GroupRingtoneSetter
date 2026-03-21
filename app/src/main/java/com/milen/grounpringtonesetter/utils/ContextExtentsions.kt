// main/java/com/milen/grounpringtonesetter/utils/ContextExtentsions.kt
package com.milen.grounpringtonesetter.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.milen.grounpringtonesetter.MainActivity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

inline fun <T> Flow<T>.collectStateIn(
    owner: LifecycleOwner,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    crossinline collector: (T) -> Unit,
) {
    owner.lifecycleScope.launch {
        owner.lifecycle.repeatOnLifecycle(minActiveState) {
            this@collectStateIn
                .distinctUntilChanged()
                .collectLatest { collector(it) }
        }
    }
}

inline fun <T> Flow<T>.collectEventsIn(
    owner: LifecycleOwner,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    crossinline collector: (T) -> Unit,
) {
    owner.lifecycleScope.launch {
        owner.lifecycle.repeatOnLifecycle(minActiveState) {
            this@collectEventsIn.collect { collector(it) } // note: NOT collectLatest
        }
    }
}

internal fun Fragment.handleLoading(loading: Boolean) =
    (requireActivity() as? MainActivity)?.handleLoading(loading)

internal fun View.hideSoftInput() {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
        ?.hideSoftInputFromWindow(windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
    clearFocus()
}

internal fun Fragment.changeMainTitle(title: String) {
    (requireActivity() as? MainActivity)?.setCustomTitle(title)
}

internal fun Context.connectivityFlow(): Flow<Boolean> = callbackFlow {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(true).isSuccess
        }

        override fun onLost(network: Network) {
            trySend(false).isSuccess
        }

        override fun onUnavailable() {
            trySend(false).isSuccess
        }
    }

    // Initial state
    trySend(isOnlineNow(cm))

    // Register default callback (API 24+; minSdk 26 is fine)
    runCatching { cm.registerDefaultNetworkCallback(callback) }
        .onFailure {
            this@connectivityFlow.trackSuppressedFailure(
                "Context.connectivityFlow.registerDefaultNetworkCallback",
                it
            )
        }

    awaitClose {
        runCatching { cm.unregisterNetworkCallback(callback) }
            .onFailure {
                this@connectivityFlow.trackSuppressedFailure(
                    "Context.connectivityFlow.unregisterNetworkCallback",
                    it
                )
            }
    }
}.distinctUntilChanged()

private fun isOnlineNow(cm: ConnectivityManager): Boolean {
    val active = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(active) ?: return false
    // VALIDATED implies actual internet; INTERNET alone can be captive/no route
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
