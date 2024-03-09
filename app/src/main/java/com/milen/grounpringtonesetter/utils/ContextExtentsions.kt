package com.milen.grounpringtonesetter.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.milen.grounpringtonesetter.MainActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

fun Context.hasInternetConnection(): Boolean =
    with(getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager) {
        this?.activeNetwork?.let {
            getNetworkCapabilities(it)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    } ?: false

fun <T> LifecycleOwner.collectScoped(flow: Flow<T>, collect: suspend (T) -> Unit) {
    lifecycleScope.launch {
        flow
            .distinctUntilChanged()
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { value ->
                collect(value)
            }
    }
}

fun Fragment.handleLoading(loading: Boolean) =
    (requireActivity() as? MainActivity)?.handleLoading(loading)


fun View.hideSoftInput() {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
        ?.hideSoftInputFromWindow(windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
    clearFocus()
}

fun Fragment.changeMainTitle(title: String) {
    (requireActivity() as? MainActivity)?.setCustomTitle(title)
}