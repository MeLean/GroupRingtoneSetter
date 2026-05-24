package com.milen.grounpringtonesetter.ui.defaulttones

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.dialog.ButtonData
import com.milen.grounpringtonesetter.customviews.dialog.DialogHandler
import com.milen.grounpringtonesetter.customviews.dialog.showAlertDialog
import com.milen.grounpringtonesetter.customviews.ui.ads.AdLoadingHelper
import com.milen.grounpringtonesetter.databinding.FragmentDeviceDefaultTonesBinding
import com.milen.grounpringtonesetter.ui.ScreenInfoProvider
import com.milen.grounpringtonesetter.ui.defaulttones.viewmodel.DeviceDefaultTonesViewModel
import com.milen.grounpringtonesetter.ui.defaulttones.viewmodel.DeviceDefaultTonesViewModelFactory
import com.milen.grounpringtonesetter.utils.changeMainTitle
import com.milen.grounpringtonesetter.utils.collectEventsIn
import com.milen.grounpringtonesetter.utils.collectStateIn
import com.milen.grounpringtonesetter.utils.currentThemeAppearance
import com.milen.grounpringtonesetter.utils.handleLoading
import com.milen.grounpringtonesetter.utils.manageVisibility

internal class DeviceDefaultTonesScreen : Fragment(), ScreenInfoProvider {
    private lateinit var binding: FragmentDeviceDefaultTonesBinding
    private lateinit var dialogHandler: DialogHandler
    private lateinit var adHelper: AdLoadingHelper

    private val viewModel: DeviceDefaultTonesViewModel by viewModels {
        DeviceDefaultTonesViewModelFactory.provideFactory(requireActivity())
    }

    private val billing by lazy(LazyThreadSafetyMode.NONE) {
        (requireActivity().application as App).billingManager
    }
    private val adsManager by lazy(LazyThreadSafetyMode.NONE) {
        (requireActivity().application as App).adsManager
    }
    private var currentEntitlement = EntitlementState.UNKNOWN
    private var canLoadAds = false

    private var activePickerToneType: DeviceDefaultToneType? = null
    private var activeFilePickerToneType: DeviceDefaultToneType? = null
    private var pendingLegacyPermissionToneType: DeviceDefaultToneType? = null

    private val tonePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val selectedToneType = activePickerToneType ?: return@registerForActivityResult
            activePickerToneType = null

            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult

            val pickedUri = result.data
                ?.getParcelableUriExtraCompat(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            viewModel.onTonePicked(selectedToneType, pickedUri)
        }

    private val writeSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.onReturnedFromWriteSettings()
        }

    private val customToneFilePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { selectedUri ->
            val selectedToneType = activeFilePickerToneType ?: return@registerForActivityResult
            activeFilePickerToneType = null
            selectedUri ?: return@registerForActivityResult
            viewModel.onCustomTonePicked(selectedToneType, selectedUri)
        }

    private val legacyStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val toneType = pendingLegacyPermissionToneType ?: return@registerForActivityResult
            pendingLegacyPermissionToneType = null
            if (!granted) {
                dialogHandler.showErrorById(R.string.need_permission_to_run)
                return@registerForActivityResult
            }
            activeFilePickerToneType = toneType
            customToneFilePickerLauncher.launch("audio/*")
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentDeviceDefaultTonesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialogHandler = DialogHandler(requireActivity())
        adHelper = AdLoadingHelper(requireActivity(), placement = "default_tones_interstitial")
        applyScreenTheme()
        binding.adBannerDefaultTones.setPlacement("default_tones_banner")

        binding.apply {
            crbChangeDefaultRingtone.setOnClickListener {
                showToneSourcePicker(DeviceDefaultToneType.RINGTONE)
            }
            crbChangeDefaultNotification.setOnClickListener {
                showToneSourcePicker(DeviceDefaultToneType.NOTIFICATION)
            }
            crbChangeDefaultAlarm.setOnClickListener {
                showToneSourcePicker(DeviceDefaultToneType.ALARM)
            }
            crbDoneDefaultTones.setOnClickListener {
                navigateBackToHome()
            }
        }

        viewModel.state.collectStateIn(viewLifecycleOwner) { state ->
            changeMainTitle(getString(R.string.device_default_tones_title))
            handleLoading(state.isLoading)
            binding.ctvDefaultRingtoneValue.text = state.ringtoneDisplayName
            binding.ctvDefaultNotificationValue.text = state.notificationDisplayName
            binding.ctvDefaultAlarmValue.text = state.alarmDisplayName
        }

        viewModel.events.collectEventsIn(viewLifecycleOwner) { event ->
            when (event) {
                is DeviceDefaultTonesEvent.LaunchTonePicker -> {
                    launchSystemTonePicker(event.config)
                }

                is DeviceDefaultTonesEvent.ShowWriteSettingsDialog -> {
                    showWriteSettingsDialog()
                }

                is DeviceDefaultTonesEvent.ShowApplyFailedDialog -> {
                    showApplyFailedDialog()
                }

                is DeviceDefaultTonesEvent.OpenIntent -> {
                    openIntentSafely(event.intent)
                }

                is DeviceDefaultTonesEvent.ShowErrorById -> {
                    dialogHandler.showErrorById(event.messageResId)
                }

                is DeviceDefaultTonesEvent.ShowInterstitialThenInfo -> {
                    adHelper.showInterstitialAd {
                        dialogHandler.showInfo(event.messageResId)
                    }
                }

                is DeviceDefaultTonesEvent.ShowInfoById -> {
                    dialogHandler.showInfo(event.messageResId)
                }
            }
        }

        billing.state.collectStateIn(viewLifecycleOwner) { entitlement ->
            currentEntitlement = entitlement
            renderBannerVisibility()
            if (entitlement == EntitlementState.NOT_OWNED && canLoadAds) {
                adHelper.preloadInterstitialAd()
            }
        }
        adsManager.canLoadAds.collectStateIn(viewLifecycleOwner) { canLoadAds ->
            this.canLoadAds = canLoadAds
            renderBannerVisibility()
            if (currentEntitlement == EntitlementState.NOT_OWNED && canLoadAds) {
                adHelper.preloadInterstitialAd()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        changeMainTitle(getString(R.string.device_default_tones_title))
        viewModel.onScreenResumed()
        if (currentEntitlement == EntitlementState.NOT_OWNED && canLoadAds) {
            adHelper.preloadInterstitialAd()
        }
    }

    private fun renderBannerVisibility() {
        binding.adBannerDefaultTones.manageVisibility(currentEntitlement, canLoadAds)
    }

    override fun getScreenInfoMessageResId(): Int = R.string.device_default_tones_info_text

    private fun applyScreenTheme() {
        val themeAppearance = requireContext().currentThemeAppearance()
        binding.llDefaultRingtoneCard.setBackgroundResource(themeAppearance.groupCardBackgroundRes)
        binding.llDefaultNotificationCard.setBackgroundResource(themeAppearance.groupCardBackgroundRes)
        binding.llDefaultAlarmCard.setBackgroundResource(themeAppearance.groupCardBackgroundRes)
    }

    private fun launchSystemTonePicker(config: TonePickerLaunchConfig) {
        activePickerToneType = config.toneType
        val pickerIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, config.toneType.ringtoneManagerType)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,
                config.toneType.allowSilentSelection
            )
            config.existingUri?.let { uri ->
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, uri)
            }
        }
        tonePickerLauncher.launch(pickerIntent)
    }

    private fun showWriteSettingsDialog() {
        requireActivity().showAlertDialog(
            titleResId = R.string.permission_required,
            message = getString(R.string.default_tone_write_settings_rationale),
            cancelButtonData = ButtonData(R.string.cancel) {
                viewModel.onWriteSettingsDialogCancelled()
            },
            confirmButtonData = ButtonData(R.string.default_tone_open_settings_action) {
                viewModel.onWriteSettingsDialogConfirmed()
            },
            isCancelable = false,
            isCancelableOnTouchOutside = false
        )
    }

    private fun showApplyFailedDialog() {
        requireActivity().showAlertDialog(
            titleResId = R.string.error,
            message = getString(R.string.default_tone_apply_failed),
            cancelButtonData = ButtonData(R.string.cancel),
            confirmButtonData = ButtonData(R.string.default_tone_open_sound_settings) {
                viewModel.onOpenSoundSettingsRequested()
            }
        )
    }

    private fun showToneSourcePicker(type: DeviceDefaultToneType) {
        requireActivity().showAlertDialog(
            titleResId = R.string.select_ringtone_source,
            message = "",
            cancelButtonData = ButtonData(R.string.ringtone_source_file) {
                launchCustomFilePicker(type)
            },
            confirmButtonData = ButtonData(R.string.ringtone_source_system) {
                viewModel.onToneChangeClicked(type)
            }
        )
    }

    private fun Intent.getParcelableUriExtraCompat(key: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }

    private fun openIntentSafely(intent: Intent) {
        val isManageWriteSettingsIntent = intent.action == Settings.ACTION_MANAGE_WRITE_SETTINGS
        val canResolve = intent.resolveActivity(requireContext().packageManager) != null
        if (!canResolve) {
            if (isManageWriteSettingsIntent) {
                viewModel.onWriteSettingsDialogCancelled()
            }
            dialogHandler.showErrorById(R.string.something_went_wrong)
            return
        }

        try {
            if (isManageWriteSettingsIntent) {
                writeSettingsLauncher.launch(intent)
            } else {
                startActivity(intent)
            }
        } catch (_: ActivityNotFoundException) {
            if (isManageWriteSettingsIntent) {
                viewModel.onWriteSettingsDialogCancelled()
            }
            dialogHandler.showErrorById(R.string.something_went_wrong)
        } catch (_: SecurityException) {
            if (isManageWriteSettingsIntent) {
                viewModel.onWriteSettingsDialogCancelled()
            }
            dialogHandler.showErrorById(R.string.something_went_wrong)
        }
    }

    private fun launchCustomFilePicker(type: DeviceDefaultToneType) {
        if (!requiresLegacyStoragePermission() || hasLegacyStoragePermission()) {
            activeFilePickerToneType = type
            customToneFilePickerLauncher.launch("audio/*")
            return
        }

        pendingLegacyPermissionToneType = type
        legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun requiresLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    private fun hasLegacyStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    private fun navigateBackToHome() {
        val navController = findNavController()
        if (navController.popBackStack(R.id.homeFragment, false)) {
            return
        }
        navController.navigateUp()
    }
}
