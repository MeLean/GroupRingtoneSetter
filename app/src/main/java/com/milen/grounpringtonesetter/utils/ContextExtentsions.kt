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

internal fun Context.internetAvailableFlow(): Flow<Boolean> = callbackFlow {
    val cm = applicationContext.getSystemService(ConnectivityManager::class.java)

    fun isOnlineNow(): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    trySend(isOnlineNow()).isSuccess

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Network became available; verify it's actually validated
            trySend(isOnlineNow()).isSuccess
        }

        override fun onLost(network: Network) {
            // Active network lost; check if another one is active/validated
            trySend(isOnlineNow()).isSuccess
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            trySend(ok).isSuccess
        }

        override fun onUnavailable() {
            trySend(false).isSuccess
        }
    }

    runCatching { cm.registerDefaultNetworkCallback(callback) }
        .onFailure { trySend(isOnlineNow()).isSuccess }

    awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
}

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