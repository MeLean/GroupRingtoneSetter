package com.milen.grounpringtonesetter.screens.nointernet

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.milen.grounpringtonesetter.databinding.FragmentNoInternetScreenBinding

class NoInternetScreen : Fragment() {
    private lateinit var binding: FragmentNoInternetScreenBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentNoInternetScreenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.topBarEndButton.setOnClickListener {
            activity?.finish()
        }
        binding.openNetworkSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }

    // Implement your connectivity checker and handle navigation if needed
}