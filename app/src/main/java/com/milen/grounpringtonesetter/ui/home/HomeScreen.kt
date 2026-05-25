// main/java/com/milen/grounpringtonesetter/ui/home/HomeScreen.kt
package com.milen.grounpringtonesetter.ui.home

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.core.view.get
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.MainActivity
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.dialog.ButtonData
import com.milen.grounpringtonesetter.customviews.dialog.DialogHandler
import com.milen.grounpringtonesetter.customviews.dialog.showAlertDialog
import com.milen.grounpringtonesetter.customviews.dialog.showCustomViewAlertDialog
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.sources.ContactSource
import com.milen.grounpringtonesetter.databinding.DialogHomePreferencesBinding
import com.milen.grounpringtonesetter.databinding.FragmentHomeScreenBinding
import com.milen.grounpringtonesetter.ui.ScreenInfoProvider
import com.milen.grounpringtonesetter.ui.accounts.AccountSelectionDialogFragment
import com.milen.grounpringtonesetter.ui.accounts.AccountSelectionDialogFragment.Companion.EXTRA_CONFIRMED
import com.milen.grounpringtonesetter.ui.accounts.AccountSelectionDialogFragment.Companion.EXTRA_SELECTED
import com.milen.grounpringtonesetter.ui.accounts.AccountSelectionDialogFragment.Companion.RESULT_KEY
import com.milen.grounpringtonesetter.ui.home.viewmodel.HomeViewModel
import com.milen.grounpringtonesetter.ui.home.viewmodel.HomeViewModelFactory
import com.milen.grounpringtonesetter.ui.picker.PickerScreenFragment
import com.milen.grounpringtonesetter.utils.GuardedNavigationFailure
import com.milen.grounpringtonesetter.utils.GuardedNavigationFailureReason
import com.milen.grounpringtonesetter.utils.RingtoneFormatValidator
import com.milen.grounpringtonesetter.utils.areAllPermissionsGranted
import com.milen.grounpringtonesetter.utils.audioPermissionsSdkBased
import com.milen.grounpringtonesetter.utils.changeMainTitle
import com.milen.grounpringtonesetter.utils.collectEventsIn
import com.milen.grounpringtonesetter.utils.collectStateIn
import com.milen.grounpringtonesetter.utils.connectivityFlow
import com.milen.grounpringtonesetter.utils.getFileNameOrEmpty
import com.milen.grounpringtonesetter.utils.handleLoading
import com.milen.grounpringtonesetter.utils.log
import com.milen.grounpringtonesetter.utils.manageVisibility
import com.milen.grounpringtonesetter.utils.navigateIfCurrentDestination
import com.milen.grounpringtonesetter.utils.navigateSingleTop
import com.milen.grounpringtonesetter.utils.parcelableOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class HomeScreen : Fragment(), GroupsAdapter.GroupItemsInteractor, ScreenInfoProvider {

    private companion object {
        private const val GROUP_SEARCH_DEBOUNCE_MS = 500L
        private const val SEARCH_ANIMATION_MS = 220L
        private const val SYSTEM_SOUNDS_TYPE =
            RingtoneManager.TYPE_RINGTONE or
                    RingtoneManager.TYPE_NOTIFICATION or
                    RingtoneManager.TYPE_ALARM
    }

    private lateinit var binding: FragmentHomeScreenBinding
    private lateinit var groupsAdapter: GroupsAdapter
    private val viewModel: HomeViewModel by activityViewModels {
        HomeViewModelFactory.provideFactory(requireActivity())
    }

    private lateinit var dialogHandler: DialogHandler
    private var groupSearchJob: Job? = null
    private var renderedSearchVisibility = false
    private var renderedThemeOption: HomeThemeOption? = null
    private var searchRevealAnimator: ValueAnimator? = null
    private var pendingAudioPermissionGroup: LabelItem? = null
    private var pendingLegacyPermissionGroup: LabelItem? = null
    private val tracker by lazy(LazyThreadSafetyMode.NONE) {
        (requireActivity().application as App).tracker
    }
    private val adsManager by lazy(LazyThreadSafetyMode.NONE) {
        (requireActivity().application as App).adsManager
    }
    private var currentEntitlement = EntitlementState.UNKNOWN
    private var canLoadAds = false

    private val permissions = mutableListOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS
    )

    private val requestMultiplePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allPermissionsGranted = permissions.entries.all { it.value }
            when {
                allPermissionsGranted -> viewModel.onPermissionsGranted()
                else -> viewModel.onPermissionsRefused()
            }
        }

    private val requestAudioPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionResults ->
            val labelItem = pendingAudioPermissionGroup ?: return@registerForActivityResult
            pendingAudioPermissionGroup = null
            if (permissionResults.values.all { it }) {
                showRingtoneSourcePicker(labelItem)
            } else {
                dialogHandler.showErrorById(R.string.need_permission_to_run)
            }
        }

    private val pickAudioFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            "pickAudioFileLauncher uri: $uri".log()
            uri?.let {
                viewModel.onRingtoneChosen(
                    activity = requireActivity(),
                    uri = it,
                    fileName = it.getFileNameOrEmpty(requireContext())
                )
            }
        }

    private val pickSystemRingtoneLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val pickedUri = result.data
                ?.getParcelableUriExtraCompat(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                ?: return@registerForActivityResult
            viewModel.onRingtoneChosen(
                activity = requireActivity(),
                uri = pickedUri,
                fileName = resolveRingtoneName(pickedUri),
                shouldValidateFormat = false
            )
        }

    private val legacyStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val labelItem = pendingLegacyPermissionGroup ?: return@registerForActivityResult
            pendingLegacyPermissionGroup = null

            if (!granted) {
                dialogHandler.showErrorById(R.string.need_permission_to_run)
                return@registerForActivityResult
            }

            launchFileRingtonePickerInternal(labelItem)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupsAdapter = GroupsAdapter(this)
        checkPermissions()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentHomeScreenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireContext()
                    .connectivityFlow()
                    .collectLatest { isOnline ->
                        viewModel.onConnectionChanged(isOnline)
                    }
            }
        }

        binding.apply {
            rwGroupItems.adapter = groupsAdapter
            setupGroupSearch()
            btnAddGroup.setOnClickListener { openCreateGroup() }
            btnEmptyAddGroup.setOnClickListener { openCreateGroup() }
            abHome.setPlacement("home_banner")
        }

        dialogHandler = DialogHandler(requireActivity())

        viewModel.state.collectStateIn(viewLifecycleOwner) { state ->
            currentEntitlement = state.entitlement
            handleLoading(state.loadingVisible)
            if (renderedThemeOption != state.displayPreferences.themeOption) {
                applyHomeTheme(
                    themeOption = state.displayPreferences.themeOption,
                    themeAppearance = state.displayPreferences.themeOption.toAppearance()
                )
                renderedThemeOption = state.displayPreferences.themeOption
            }

            if (!state.arePermissionsGranted) {
                requestMultiplePermissions.launch(permissions.toTypedArray())
            } else {
                groupsAdapter.submitList(state.labelItems)
            }

            binding.apply {
                if (state.scrollToBottom) {
                    try {
                        val itemCount = groupsAdapter.itemCount
                        if (itemCount > 0) {
                            val lastPosition = itemCount - 1
                            rwGroupItems.smoothScrollToPosition(lastPosition)
                        }
                    } catch (e: Exception) {
                        (e.localizedMessage ?: e.toString()).log()
                    }
                }

                noItemDisclaimer.isVisible =
                    state.labelItems.isEmpty() &&
                        !state.isLoading &&
                        state.arePermissionsGranted
                noItemDisclaimer.setText(resolveHomeEmptyStateMessageRes(state))
                btnEmptyAddGroup.isVisible =
                    noItemDisclaimer.isVisible && shouldShowHomeEmptyAddGroupButton(state)
                rwGroupItems.isInvisible = noItemDisclaimer.isVisible

                val currentQuery = civGroupSearch.getText()
                if (currentQuery != state.groupSearchQuery) {
                    civGroupSearch.setText(state.groupSearchQuery)
                }

                if (state.isLoading) {
                    searchRevealAnimator?.cancel()
                    flGroupSearchOverlay.clipBounds = null
                    flGroupSearchOverlay.isVisible = false
                    ctcibToggleSearch.isVisible = false
                    ctcibActionsMenu.isVisible = false
                    btnAddGroup.isVisible = false
                    btnEmptyAddGroup.isVisible = false
                    btnAddGroup.translationX = 0f
                    renderedSearchVisibility = false
                } else {
                    ctcibToggleSearch.apply {
                        isVisible = true
                        setOnClickListener {
                            viewModel.onGroupSearchVisibilityChanged(!state.isGroupSearchVisible)
                        }
                    }
                    ctcibActionsMenu.apply {
                        isVisible = true
                        setOnClickListener {
                            showActionsMenu(
                                canChangeAccount = state.canChangeSource,
                                currentPreferences = state.displayPreferences
                            )
                        }
                    }
                    updateSearchToggleIcon(state.isGroupSearchVisible)

                    renderGroupSearchVisibility(
                        isVisible = state.isGroupSearchVisible,
                        animate = state.isGroupSearchVisible != renderedSearchVisibility
                    )
                }

                renderBannerVisibility()

                llBillingsActions.isVisible = state.entitlement != EntitlementState.OWNED

                btnRemoveAds.apply {
                    isVisible = state.entitlement == EntitlementState.NOT_OWNED
                    isEnabled = !state.isPurchaseInProgress
                    setOnClickListener {
                        if (state.isPurchaseInProgress) return@setOnClickListener
                        isEnabled = false
                        viewModel.startPurchase(requireActivity())
                    }
                }

                ctvValidationPurchases.isVisible = state.entitlement == EntitlementState.PENDING
            }
        }

        adsManager.canLoadAds.collectStateIn(viewLifecycleOwner) { canLoadAds ->
            this.canLoadAds = canLoadAds
            renderBannerVisibility()
        }

        viewModel.events.collectEventsIn(viewLifecycleOwner) { event ->
            when (event) {
                is HomeEvent.AskSourceSelection ->
                    AccountSelectionDialogFragment.show(this, event.sources, event.selected)

                is HomeEvent.ConnectionLost ->
                    findNavController().navigateSingleTop(R.id.noInternetFragment)

                is HomeEvent.NavigateToRename ->
                    navigateFromHome(
                        eventName = "NavigateToRename",
                        actionId = R.id.action_home_to_picker,
                        args = PickerScreenFragment.argsForRename(event.group)
                    )

                is HomeEvent.NavigateToManageContacts ->
                    navigateFromHome(
                        eventName = "NavigateToManageContacts",
                        actionId = R.id.action_home_to_picker,
                        args = PickerScreenFragment.argsForManage(event.group)
                    )

                is HomeEvent.NavigateToCreateGroup ->
                    navigateFromHome(
                        eventName = "NavigateToCreateGroup",
                        actionId = R.id.action_home_to_picker,
                        args = PickerScreenFragment.argsForCreate()
                    )

                is HomeEvent.NavigateToDeviceDefaultTones ->
                    navigateFromHome(
                        eventName = "NavigateToDeviceDefaultTones",
                        actionId = R.id.action_home_to_deviceDefaultTones
                    )

                is HomeEvent.ShowAdUnavailableDialog ->
                    requireActivity().showAlertDialog(
                        titleResId = R.string.info,
                        message = getString(R.string.ad_unavailable_after_ringtone_change),
                        cancelButtonData = ButtonData(R.string.cancel),
                        confirmButtonData = ButtonData(R.string.ad_free_forever) {
                            viewModel.startPurchase(requireActivity())
                        }
                    )

                is HomeEvent.ShowErrorById -> dialogHandler.showErrorById(event.strRes)
                is HomeEvent.ShowErrorText -> dialogHandler.showError(event.message)
                is HomeEvent.ShowInfoText -> dialogHandler.showInfo(event.strRes)
            }
        }

        parentFragmentManager.setFragmentResultListener(
            RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val confirmed = bundle.getBoolean(EXTRA_CONFIRMED)
            if (confirmed) {
                val selectedSource: ContactSource? = bundle.parcelableOrNull(EXTRA_SELECTED)
                viewModel.onAccountsSelected(selectedSource)
            } else {
                viewModel.onSourceSelectionDismissed()
            }
        }
    }

    override fun onStop() {
        groupSearchJob?.cancel()
        searchRevealAnimator?.cancel()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onHomeResumed(requireActivity())
        changeMainTitle(getString(R.string.app_name))
    }

    private fun renderBannerVisibility() {
        binding.abHome.manageVisibility(currentEntitlement, canLoadAds)
    }

    private fun checkPermissions() {
        when {
            requireContext().areAllPermissionsGranted(permissions = permissions) ->
                viewModel.onPermissionsGranted()

            else -> viewModel.onNoPermissions()
        }
    }

    private fun setupGroupSearch() {
        binding.civGroupSearch.setSoftDoneCLicked {
            submitSearchImmediately()
        }

        binding.civGroupSearch.setOnTextChangedListener { query ->
            if (!renderedSearchVisibility) return@setOnTextChangedListener
            groupSearchJob?.cancel()
            groupSearchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(GROUP_SEARCH_DEBOUNCE_MS)
                viewModel.onGroupSearchQueryUpdated(query)
            }
        }
    }

    private fun submitSearchImmediately() {
        groupSearchJob?.cancel()
        viewModel.onGroupSearchQueryUpdated(binding.civGroupSearch.getText())
        binding.civGroupSearch.clearFocus()
    }

    private fun renderGroupSearchVisibility(
        isVisible: Boolean,
        animate: Boolean,
    ) {
        renderedSearchVisibility = isVisible
        if (!animate) {
            applySearchStateInstant(isVisible)
            return
        }
        animateSearchTransition(isVisible)
    }

    private fun applySearchStateInstant(
        isVisible: Boolean,
    ) {
        val searchView = binding.flGroupSearchOverlay
        if (isVisible) {
            searchView.isVisible = true
        }

        if (isVisible && (
                searchView.width == 0 ||
                        binding.btnAddGroup.width == 0
                )
        ) {
            binding.clTopActions.doOnLayout {
                applySearchStateInstant(
                    isVisible = isVisible
                )
            }
            return
        }

        val finalFraction = if (isVisible) 1f else 0f
        if (!isVisible) {
            binding.civGroupSearch.clearFocus()
            searchView.isVisible = false
            searchView.clipBounds = null
        }
        applySearchClipFraction(finalFraction)
        val pushDistance = calculateButtonsPushDistance()
        val translationX = -pushDistance * finalFraction
        binding.btnAddGroup.translationX = translationX
        binding.btnAddGroup.isVisible = true
    }

    private fun animateSearchTransition(
        expand: Boolean,
    ) {
        val searchView = binding.flGroupSearchOverlay
        val addButton = binding.btnAddGroup

        addButton.isVisible = true
        if (expand) {
            searchView.isVisible = true
            applySearchClipFraction(0f)
        }

        val runAnimation = {
            val startFraction = if (expand) 0f else 1f
            val endFraction = if (expand) 1f else 0f
            val pushDistance = calculateButtonsPushDistance()

            searchRevealAnimator?.cancel()
            val animator = ValueAnimator.ofFloat(startFraction, endFraction)
            searchRevealAnimator = animator
            animator.duration = SEARCH_ANIMATION_MS
            animator.addUpdateListener { valueAnimator ->
                val fraction = valueAnimator.animatedValue as Float
                applySearchClipFraction(fraction)
                val translationX = -pushDistance * fraction
                addButton.translationX = translationX
            }
            animator.addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    searchRevealAnimator = null
                    if (cancelled) return
                    if (!expand) {
                        binding.civGroupSearch.clearFocus()
                        searchView.isVisible = false
                        searchView.clipBounds = null
                        addButton.translationX = 0f
                    } else {
                        searchView.clipBounds = null
                    }
                }
            })
            animator.start()
        }

        if (searchView.width == 0 || searchView.height == 0) {
            searchView.doOnLayout { runAnimation() }
        } else {
            runAnimation()
        }
    }

    private fun applySearchClipFraction(fraction: Float) {
        val searchView = binding.flGroupSearchOverlay
        val width = searchView.width
        val height = searchView.height
        if (width <= 0 || height <= 0) return

        if (fraction <= 0f) {
            searchView.clipBounds = Rect(width, 0, width, height)
            return
        }
        if (fraction >= 1f) {
            searchView.clipBounds = null
            return
        }

        val visibleWidth = (width * fraction).toInt().coerceIn(0, width)
        val left = (width - visibleWidth).coerceIn(0, width)
        searchView.clipBounds = Rect(left, 0, width, height)
    }

    private fun calculateButtonsPushDistance(): Float {
        val addButton = binding.btnAddGroup
        val buffer = (resources.displayMetrics.density * 16f).toInt()
        return (addButton.right + buffer).toFloat()
    }

    private fun applyHomeTheme(
        themeOption: HomeThemeOption,
        themeAppearance: HomeThemeAppearance,
    ) {
        val context = requireContext()
        val screenBackgroundColor =
            ContextCompat.getColor(context, themeAppearance.screenBackgroundColorRes)
        val textColor = ContextCompat.getColor(context, themeAppearance.textColorRes)
        val iconTintColor = ContextCompat.getColor(context, themeAppearance.iconTintColorRes)
        val actionButtonBackground =
            ContextCompat.getColor(context, themeAppearance.actionButtonBackgroundColorRes)
        val actionButtonText =
            ContextCompat.getColor(context, themeAppearance.actionButtonTextColorRes)
        val searchStrokeColor =
            ContextCompat.getColor(context, themeAppearance.searchStrokeColorRes)
        val searchHintColor =
            ContextCompat.getColor(context, themeAppearance.searchHintColorRes)
        val contentBackgroundColor = if (themeOption == HomeThemeOption.CLASSIC) {
            Color.TRANSPARENT
        } else {
            screenBackgroundColor
        }

        binding.root.setBackgroundColor(contentBackgroundColor)
        binding.clTopActions.setBackgroundColor(contentBackgroundColor)
        binding.rwGroupItems.setBackgroundColor(contentBackgroundColor)
        binding.llBillingsActions.setBackgroundColor(contentBackgroundColor)
        binding.noItemDisclaimer.setTextColor(textColor)
        binding.ctvValidationPurchases.setTextColor(textColor)
        binding.btnAddGroup.setColors(actionButtonBackground, actionButtonText)
        binding.btnRemoveAds.setColors(actionButtonBackground, actionButtonText)
        binding.ctcibToggleSearch.setIconTint(iconTintColor)
        binding.ctcibActionsMenu.setIconTint(iconTintColor)
        binding.civGroupSearch.applyColors(
            textColor = textColor,
            hintColor = searchHintColor,
            strokeColor = searchStrokeColor
        )
        groupsAdapter.updateThemeAppearance(themeAppearance)
    }

    private fun openCreateGroup() {
        viewModel.setUpGroupCreateRequest()
    }

    private fun showActionsMenu(
        canChangeAccount: Boolean,
        currentPreferences: HomeDisplayPreferences,
    ) {
        val popup = PopupMenu(requireContext(), binding.ctcibActionsMenu)
        popup.menuInflater.inflate(R.menu.home_actions_dropdown, popup.menu)
        popup.setForceShowIcon(true)
        val iconColor = resolvePopupTextColor()
        repeat(popup.menu.size) { index ->
            popup.menu[index].icon?.mutate()?.setTint(iconColor)
        }
        popup.menu.findItem(R.id.actionChangeAccount)?.isVisible = canChangeAccount
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.actionHomeInfo -> {
                    (requireActivity() as MainActivity).showAppInfoDialog()
                    true
                }

                R.id.actionSetDeviceDefaultTones -> {
                    viewModel.onDeviceDefaultTonesClicked()
                    true
                }

                R.id.actionUserPreferences -> {
                    viewModel.onUserPreferencesClicked()
                    showUserPreferencesDialog(currentPreferences)
                    true
                }

                R.id.actionResetAllRingtones -> {
                    showResetAllRingtonesDialog()
                    true
                }

                R.id.actionChangeAccount -> {
                    viewModel.onSelectAccountClicked()
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun showResetAllRingtonesDialog() {
        if (!requireContext().areAllPermissionsGranted(permissions)) {
            requestMultiplePermissions.launch(permissions.toTypedArray())
            return
        }

        requireActivity().showAlertDialog(
            titleResId = R.string.reset_all_ringtones,
            message = getString(R.string.reset_all_ringtones_description),
            cancelButtonData = ButtonData(R.string.cancel),
            confirmButtonData = ButtonData { viewModel.onResetAllRingtonesConfirmed() }
        )
    }

    private fun showUserPreferencesDialog(currentPreferences: HomeDisplayPreferences) {
        val dialogBinding = DialogHomePreferencesBinding.inflate(layoutInflater)
        applyUserPreferencesDialogTheme(
            dialogBinding = dialogBinding,
            themeAppearance = currentPreferences.themeOption.toAppearance()
        )
        dialogBinding.selectThemeOption(currentPreferences.themeOption)
        dialogBinding.selectSortOption(currentPreferences.groupSortOption)

        requireActivity().showCustomViewAlertDialog(
            titleResId = R.string.user_preferences,
            customView = dialogBinding.root,
            cancelButtonData = ButtonData(R.string.cancel),
            confirmButtonData = ButtonData(R.string.confirm) {
                val updatedPreferences = HomeDisplayPreferences(
                    themeOption = dialogBinding.selectedThemeOption(),
                    groupSortOption = dialogBinding.selectedSortOption()
                )

                if (updatedPreferences == currentPreferences) {
                    return@ButtonData
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.persistHomeDisplayPreferences(updatedPreferences)

                    val app = requireActivity().application as App
                    app.updateThemeOption(updatedPreferences.themeOption)
                    if (updatedPreferences.themeOption != currentPreferences.themeOption && isAdded) {
                        requireActivity().recreate()
                    }
                }
            }
        )
    }

    private fun applyUserPreferencesDialogTheme(
        dialogBinding: DialogHomePreferencesBinding,
        themeAppearance: HomeThemeAppearance,
    ) {
        val context = requireContext()
        val textColor = ContextCompat.getColor(context, themeAppearance.textColorRes)
        val dialogBackgroundColor =
            ContextCompat.getColor(context, themeAppearance.dialogBackgroundColorRes)
        val checkedColor =
            ContextCompat.getColor(context, themeAppearance.dialogActionTextColorRes)
        val uncheckedColor =
            ContextCompat.getColor(context, themeAppearance.searchHintColorRes)
        val buttonTint = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(
                checkedColor,
                uncheckedColor
            )
        )

        dialogBinding.root.setBackgroundColor(dialogBackgroundColor)
        dialogBinding.llDialogContent.setBackgroundColor(dialogBackgroundColor)
        dialogBinding.ctvThemeLabel.setTextColor(textColor)
        dialogBinding.ctvSortLabel.setTextColor(textColor)
        listOf(
            dialogBinding.rbThemeClassic,
            dialogBinding.rbThemeDarkHighContrast,
            dialogBinding.rbThemeLightHighContrast,
            dialogBinding.rbSortCurrentOrder,
            dialogBinding.rbSortAscending,
            dialogBinding.rbSortDescending
        ).forEach { radioButton ->
            radioButton.setTextColor(textColor)
            radioButton.buttonTintList = buttonTint
        }
    }

    private fun resolvePopupTextColor(): Int {
        val typedValue = TypedValue()
        val resolved = requireContext().theme.resolveAttribute(
            android.R.attr.textColorPrimary,
            typedValue,
            true
        )
        if (!resolved) {
            return ContextCompat.getColor(requireContext(), R.color.textColor)
        }
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(requireContext(), typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun updateSearchToggleIcon(isSearchVisible: Boolean) {
        if (isSearchVisible) {
            binding.ctcibToggleSearch.setIcon(R.drawable.ic_close_24)
            binding.ctcibToggleSearch.applyButtonContentDescription(getString(R.string.close))
            return
        }
        binding.ctcibToggleSearch.setIcon(R.drawable.ic_search_24)
        binding.ctcibToggleSearch.applyButtonContentDescription(getString(R.string.search_group_hint))
    }

    override fun onManageContacts(labelItem: LabelItem): Unit =
        requireActivity().showAlertDialog(
            titleResId = R.string.manage_contacts_group_name,
            message = getString(R.string.manage_contacts_group_name_desc),
            cancelButtonData = ButtonData(R.string.cancel),
            confirmButtonData = ButtonData { viewModel.setUpContactsManaging(labelItem) }
        )

    override fun onEditName(labelItem: LabelItem): Unit =
        requireActivity().showAlertDialog(
            titleResId = R.string.edit_group_name,
            message = getString(R.string.edit_group_name_desc),
            cancelButtonData = ButtonData(R.string.cancel),
            confirmButtonData = ButtonData { viewModel.setUpGroupNameEditing(labelItem) }
        )

    override fun onGroupDelete(labelItem: LabelItem): Unit =
        requireActivity().showAlertDialog(
            titleResId = R.string.delete_group,
            message = getString(R.string.delete_group_desc),
            cancelButtonData = ButtonData(R.string.cancel),
            confirmButtonData = ButtonData { viewModel.onGroupDeleted(labelItem) }
        )

    override fun onChoseRingtoneIntent(labelItem: LabelItem) {
        if (requireContext().areAllPermissionsGranted(permissions = permissions)) {
            val audioPermissions = audioPermissionsSdkBased()
            if (audioPermissions.isEmpty() ||
                requireContext().areAllPermissionsGranted(audioPermissions)
            ) {
                showRingtoneSourcePicker(labelItem)
            } else {
                pendingAudioPermissionGroup = labelItem
                requestAudioPermissions.launch(audioPermissions.toTypedArray())
            }
        } else {
            viewModel.onNoPermissions()
        }
    }

    private fun showRingtoneSourcePicker(labelItem: LabelItem) {
        requireActivity().showAlertDialog(
            titleResId = R.string.select_ringtone_source,
            message = "",
            cancelButtonData = ButtonData(R.string.ringtone_source_file) {
                launchFileRingtonePicker(labelItem)
            },
            confirmButtonData = ButtonData(R.string.ringtone_source_system) {
                launchSystemRingtonePicker(labelItem)
            }
        )
    }

    private fun launchFileRingtonePicker(labelItem: LabelItem) {
        if (requiresLegacyStoragePermission() && !hasLegacyStoragePermission()) {
            pendingLegacyPermissionGroup = labelItem
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        launchFileRingtonePickerInternal(labelItem)
    }

    private fun launchFileRingtonePickerInternal(labelItem: LabelItem) {
        viewModel.selectingGroup = labelItem
        pickAudioFileLauncher.launch(RingtoneFormatValidator.SUPPORTED_MIME_TYPES.toTypedArray())
    }

    private fun launchSystemRingtonePicker(labelItem: LabelItem) {
        viewModel.selectingGroup = labelItem
        val existingRingtoneUri = labelItem.ringtoneUriList
            .firstOrNull()
            ?.let { uriStr -> runCatching { uriStr.toUri() }.getOrNull() }
        val pickerIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, SYSTEM_SOUNDS_TYPE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                Settings.System.DEFAULT_RINGTONE_URI
            )
            existingRingtoneUri?.let { uri ->
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, uri)
            }
        }
        pickSystemRingtoneLauncher.launch(pickerIntent)
    }

    private fun navigateFromHome(
        eventName: String,
        actionId: Int,
        args: Bundle? = null,
    ) {
        val failure = findNavController().navigateIfCurrentDestination(
            expectedDestinationId = R.id.homeFragment,
            actionId = actionId,
            args = args
        ) ?: return

        trackSuppressedHomeNavigation(eventName, failure)
    }

    private fun trackSuppressedHomeNavigation(
        eventName: String,
        failure: GuardedNavigationFailure,
    ) {
        tracker.trackEvent(
            "home_navigation_suppressed",
            mapOf(
                "event" to eventName,
                "action_id" to failure.actionId,
                "expected_destination_id" to failure.expectedDestinationId,
                "actual_destination_id" to (failure.actualDestinationId?.toString() ?: "null"),
                "graph_id" to failure.graphId,
                "reason" to failure.reason.name
            )
        )

        if (failure.reason == GuardedNavigationFailureReason.GRAPH_ROOT ||
            failure.reason == GuardedNavigationFailureReason.NO_CURRENT_DESTINATION
        ) {
            tracker.trackError(
                HomeNavigationSuppressedException(
                    eventName = eventName,
                    actionId = failure.actionId,
                    expectedDestinationId = failure.expectedDestinationId,
                    actualDestinationId = failure.actualDestinationId,
                    graphId = failure.graphId,
                    failureReason = failure.reason
                )
            )
        }
    }

    private fun resolveRingtoneName(uri: Uri): String {
        val context = requireContext()
        val title =
            runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()
        if (!title.isNullOrBlank()) return title

        val fileName = uri.getFileNameOrEmpty(context)
        if (fileName.isNotBlank()) return fileName

        return getString(R.string.file_name_not_accessible)
    }

    private fun Intent.getParcelableUriExtraCompat(key: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }

    private fun requiresLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    private fun hasLegacyStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    override fun getScreenInfoMessageResId(): Int = R.string.home_info_text

    override fun getToolbarInfoMessageResId(): Int? = null
}

private fun DialogHomePreferencesBinding.selectThemeOption(option: HomeThemeOption) {
    rgThemeOptions.check(
        when (option) {
            HomeThemeOption.CLASSIC -> rbThemeClassic.id
            HomeThemeOption.DARK_HIGH_CONTRAST -> rbThemeDarkHighContrast.id
            HomeThemeOption.LIGHT_HIGH_CONTRAST -> rbThemeLightHighContrast.id
        }
    )
}

private fun DialogHomePreferencesBinding.selectedThemeOption(): HomeThemeOption = when (
    rgThemeOptions.checkedRadioButtonId
) {
    rbThemeDarkHighContrast.id -> HomeThemeOption.DARK_HIGH_CONTRAST
    rbThemeLightHighContrast.id -> HomeThemeOption.LIGHT_HIGH_CONTRAST
    else -> HomeThemeOption.CLASSIC
}

private fun DialogHomePreferencesBinding.selectSortOption(option: GroupSortOption) {
    rgSortOptions.check(
        when (option) {
            GroupSortOption.CURRENT_ORDER -> rbSortCurrentOrder.id
            GroupSortOption.ALPHABETICAL_ASC -> rbSortAscending.id
            GroupSortOption.ALPHABETICAL_DESC -> rbSortDescending.id
        }
    )
}

private fun DialogHomePreferencesBinding.selectedSortOption(): GroupSortOption = when (
    rgSortOptions.checkedRadioButtonId
) {
    rbSortAscending.id -> GroupSortOption.ALPHABETICAL_ASC
    rbSortDescending.id -> GroupSortOption.ALPHABETICAL_DESC
    else -> GroupSortOption.CURRENT_ORDER
}
