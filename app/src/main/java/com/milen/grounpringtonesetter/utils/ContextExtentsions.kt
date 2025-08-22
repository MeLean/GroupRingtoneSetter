package com.milen.grounpringtonesetter.utils

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
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

fun Context.hasInternetConnection(): Boolean =
    with(getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager) {
        this?.activeNetwork?.let {
            getNetworkCapabilities(it)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    && getNetworkCapabilities(it)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
    } ?: false

internal fun Context.internetAvailableFlow(): Flow<Boolean> = callbackFlow {
    val app = this@internetAvailableFlow.applicationContext
    val cm = app.getSystemService(ConnectivityManager::class.java)

    fun snapshot() = app.hasInternetConnection()
    trySend(snapshot())

    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            trySend(ok)
        }

        override fun onAvailable(network: Network) {
            trySend(snapshot())
        }

        override fun onLost(network: Network) {
            trySend(snapshot())
        }

        override fun onUnavailable() {
            trySend(false)
        }
    }

    val req = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    cm.registerNetworkCallback(req, cb)

    awaitClose { cm.unregisterNetworkCallback(cb) }
}.distinctUntilChanged()

internal fun Activity.subscribeForConnectivityChanges(block: (Boolean) -> Unit) {
    (this as? AppCompatActivity)?.lifecycleScope?.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            internetAvailableFlow()
                .distinctUntilChanged()
                .collect { isOnline ->
                    block(isOnline)
                }
        }
    }
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