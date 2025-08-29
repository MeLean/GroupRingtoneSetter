package com.milen.grounpringtonesetter.ui.nointernet

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.databinding.FragmentNoInternetScreenBinding
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal class NoInternetScreen : Fragment() {
    private lateinit var binding: FragmentNoInternetScreenBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentNoInternetScreenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.topBarEndButton.setOnClickListener { activity?.finish() }
        binding.openNetworkSettingsButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }

        // ✅ Lifecycle-aware collection: auto-stops when view stops/destroys
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireContext()
                    .connectivityFlow() // defined below in this same file
                    .distinctUntilChanged()
                    .collectLatest { isOnline ->
                        // Guard: fragment must be attached + navController available
                        val nav = navControllerOrNull() ?: return@collectLatest
                        if (isOnline && nav.currentDestination?.id == R.id.noInternetFragment) {
                            // Pop entire graph and go Home (no double-navigate)
                            val opts = NavOptions.Builder()
                                .setPopUpTo(nav.graph.id, true)
                                .setLaunchSingleTop(true)
                                .build()
                            runCatching { nav.navigate(R.id.homeFragment, null, opts) }
                        }
                    }
            }
        }
    }

    // Safe NavController access
    private fun navControllerOrNull(): NavController? {
        if (!isAdded) return null
        return runCatching { findNavController() }.getOrNull()
    }
}

private fun Context.connectivityFlow(): Flow<Boolean> = callbackFlow {
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
        .onFailure { /* if it ever fails, we at least emitted initial state */ }

    awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
}.distinctUntilChanged()

private fun isOnlineNow(cm: ConnectivityManager): Boolean {
    val active = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(active) ?: return false
    // VALIDATED implies actual internet; INTERNET alone can be captive/no route
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}