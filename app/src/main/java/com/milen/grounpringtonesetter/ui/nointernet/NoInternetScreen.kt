package com.milen.grounpringtonesetter.ui.nointernet

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.databinding.FragmentNoInternetScreenBinding
import com.milen.grounpringtonesetter.ui.ScreenInfoProvider
import com.milen.grounpringtonesetter.utils.currentThemeAppearance
import com.milen.grounpringtonesetter.utils.trackSuppressedFailure
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal class NoInternetScreen : Fragment(), ScreenInfoProvider {
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
        applyScreenTheme()

        binding.topBarEndButton.setOnClickListener { activity?.finish() }
        binding.openNetworkSettingsButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireContext()
                    .connectivityFlow() // defined below in this same file
                    .distinctUntilChanged()
                    .collectLatest { isOnline ->
                        val nav = navControllerOrNull() ?: return@collectLatest
                        if (isOnline) {
                            runCatching { nav.popBackFromNoInternetScreen() }
                                .onFailure {
                                    context.trackSuppressedFailure(
                                        "NoInternetScreen.popBackFromNoInternetScreen",
                                        it
                                    )
                                }
                        }
                    }
            }
        }
    }

    private fun applyScreenTheme() {
        val themeAppearance = requireContext().currentThemeAppearance()
        val textColor = ContextCompat.getColor(requireContext(), themeAppearance.textColorRes)
        val iconTintColor =
            ContextCompat.getColor(requireContext(), themeAppearance.iconTintColorRes)
        val actionBackgroundColor = ContextCompat.getColor(
            requireContext(),
            themeAppearance.actionButtonBackgroundColorRes
        )
        val actionTextColor = ContextCompat.getColor(
            requireContext(),
            themeAppearance.actionButtonTextColorRes
        )

        binding.noInternetDescription.setTextColor(textColor)
        binding.backgroundImage.imageTintList = ColorStateList.valueOf(iconTintColor)
        binding.openNetworkSettingsButton.backgroundTintList =
            ColorStateList.valueOf(actionBackgroundColor)
        binding.openNetworkSettingsButton.setTextColor(actionTextColor)
        binding.topBarEndButton.backgroundTintList = ColorStateList.valueOf(actionBackgroundColor)
        binding.topBarEndButton.setTextColor(actionTextColor)
    }

    override fun getScreenInfoMessageResId(): Int = R.string.no_internet_info_text

    private fun navControllerOrNull(): NavController? {
        if (!isAdded) return null
        return runCatching { findNavController() }
            .onFailure { context.trackSuppressedFailure("NoInternetScreen.findNavController", it) }
            .getOrNull()
    }
}

internal fun NavController.popBackFromNoInternetScreen(): Boolean {
    if (currentDestination?.id != R.id.noInternetFragment) return false
    return popBackStack()
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
        .onFailure {
            this@connectivityFlow.trackSuppressedFailure(
                "NoInternetScreen.connectivityFlow.registerDefaultNetworkCallback",
                it
            )
        }

    awaitClose {
        runCatching { cm.unregisterNetworkCallback(callback) }
            .onFailure {
                this@connectivityFlow.trackSuppressedFailure(
                    "NoInternetScreen.connectivityFlow.unregisterNetworkCallback",
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
