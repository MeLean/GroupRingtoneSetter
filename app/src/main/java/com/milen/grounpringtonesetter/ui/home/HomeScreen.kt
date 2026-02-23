// main/java/com/milen/grounpringtonesetter/ui/home/HomeScreen.kt
package com.milen.grounpringtonesetter.ui.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.dialog.ButtonData
import com.milen.grounpringtonesetter.customviews.dialog.DialogHandler
import com.milen.grounpringtonesetter.customviews.dialog.showAlertDialog
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.accounts.AccountId
import com.milen.grounpringtonesetter.databinding.FragmentHomeScreenBinding
import com.milen.grounpringtonesetter.ui.accounts.AccountSelectionDialogFragment
import com.milen.grounpringtonesetter.ui.accounts.AccountSelectionDialogFragment.Companion.EXTRA_SELECTED
import com.milen.grounpringtonesetter.ui.accounts.AccountSelectionDialogFragment.Companion.RESULT_KEY
import com.milen.grounpringtonesetter.ui.home.viewmodel.HomeViewModel
import com.milen.grounpringtonesetter.ui.home.viewmodel.HomeViewModelFactory
import com.milen.grounpringtonesetter.ui.picker.PickerScreenFragment
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
import com.milen.grounpringtonesetter.utils.navigateSingleTop
import com.milen.grounpringtonesetter.utils.parcelableOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class HomeScreen : Fragment(), GroupsAdapter.GroupItemsInteractor {

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
    private var searchRevealAnimator: ValueAnimator? = null

    private val permissions = mutableListOf(
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.WRITE_CONTACTS
    ).also { it.addAll(audioPermissionsSdkBased()) }

    private val requestMultiplePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allPermissionsGranted = permissions.entries.all { it.value }
            when {
                allPermissionsGranted -> viewModel.onPermissionsGranted()
                else -> viewModel.onPermissionsRefused()
            }
        }

    private val pickAudioFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            "pickAudioFileLauncher uri: $uri".log()
            uri?.let { viewModel.onRingtoneChosen(it, it.getFileNameOrEmpty(requireContext())) }
        }

    private val pickSystemRingtoneLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val pickedUri = result.data
                ?.getParcelableUriExtraCompat(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                ?: return@registerForActivityResult
            viewModel.onRingtoneChosen(
                uri = pickedUri,
                fileName = resolveRingtoneName(pickedUri),
                shouldValidateFormat = false
            )
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
        }

        dialogHandler = DialogHandler(requireActivity())

        viewModel.state.collectStateIn(viewLifecycleOwner) { state ->
            handleLoading(state.loadingVisible)

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

                val currentQuery = civGroupSearch.getText()
                if (currentQuery != state.groupSearchQuery) {
                    civGroupSearch.setText(state.groupSearchQuery)
                }

                if (state.isLoading) {
                    searchRevealAnimator?.cancel()
                    flGroupSearchOverlay.clipBounds = null
                    flGroupSearchOverlay.isVisible = false
                    ctcibToggleSearch.isVisible = false
                    btnAddGroup.isVisible = false
                    btnSelectAccount.isVisible = false
                    btnAddGroup.translationX = 0f
                    btnSelectAccount.translationX = 0f
                    renderedSearchVisibility = false
                } else {
                    ctcibToggleSearch.apply {
                        isVisible = true
                        setOnClickListener {
                            viewModel.onGroupSearchVisibilityChanged(!state.isGroupSearchVisible)
                        }
                    }
                    updateSearchToggleIcon(state.isGroupSearchVisible)

                    btnAddGroup.setOnClickListener { viewModel.setUpGroupCreateRequest() }
                    btnSelectAccount.setOnClickListener { viewModel.onSelectAccountClicked() }

                    renderGroupSearchVisibility(
                        isVisible = state.isGroupSearchVisible,
                        canShowAccountButton = state.canChangeAccount,
                        animate = state.isGroupSearchVisible != renderedSearchVisibility
                    )
                }

                abHome.manageVisibility(state.entitlement)

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

        viewModel.events.collectEventsIn(viewLifecycleOwner) { event ->
            when (event) {
                is HomeEvent.AskAccountSelection ->
                    AccountSelectionDialogFragment.show(this, event.accounts, event.selected)

                is HomeEvent.ConnectionLost ->
                    findNavController().navigateSingleTop(R.id.noInternetFragment)

                is HomeEvent.NavigateToRename ->
                    findNavController().navigate(
                        R.id.action_home_to_picker,
                        PickerScreenFragment.argsForRename(event.group)
                    )

                is HomeEvent.NavigateToManageContacts ->
                    findNavController().navigate(
                        R.id.action_home_to_picker,
                        PickerScreenFragment.argsForManage(event.group)
                    )

                is HomeEvent.NavigateToCreateGroup ->
                    findNavController().navigate(
                        R.id.action_home_to_picker,
                        PickerScreenFragment.argsForCreate()
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
            val selectedAccount: AccountId? = bundle.parcelableOrNull(EXTRA_SELECTED)
            viewModel.onAccountsSelected(selectedAccount)
        }
    }

    override fun onStop() {
        groupSearchJob?.cancel()
        searchRevealAnimator?.cancel()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onHomeResumed()
        changeMainTitle(getString(R.string.app_name))
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
        canShowAccountButton: Boolean,
        animate: Boolean,
    ) {
        renderedSearchVisibility = isVisible
        if (!animate) {
            applySearchStateInstant(isVisible, canShowAccountButton)
            return
        }
        animateSearchTransition(isVisible, canShowAccountButton)
    }

    private fun applySearchStateInstant(
        isVisible: Boolean,
        canShowAccountButton: Boolean,
    ) {
        val searchView = binding.flGroupSearchOverlay
        if (isVisible) {
            searchView.isVisible = true
        }

        if (isVisible && (
                searchView.width == 0 ||
                    binding.btnAddGroup.width == 0 ||
                    (canShowAccountButton && binding.btnSelectAccount.width == 0)
                )
        ) {
            binding.clTopActions.doOnLayout {
                applySearchStateInstant(
                    isVisible = isVisible,
                    canShowAccountButton = canShowAccountButton
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
        val pushDistance = calculateButtonsPushDistance(canShowAccountButton)
        val translationX = -pushDistance * finalFraction
        binding.btnAddGroup.translationX = translationX
        binding.btnSelectAccount.translationX = translationX
        binding.btnAddGroup.isVisible = true
        binding.btnSelectAccount.isVisible = canShowAccountButton
    }

    private fun animateSearchTransition(
        expand: Boolean,
        canShowAccountButton: Boolean,
    ) {
        val searchView = binding.flGroupSearchOverlay
        val addButton = binding.btnAddGroup
        val accountButton = binding.btnSelectAccount

        addButton.isVisible = true
        accountButton.isVisible = canShowAccountButton
        if (expand) {
            searchView.isVisible = true
            applySearchClipFraction(0f)
        }

        val runAnimation = {
            val startFraction = if (expand) 0f else 1f
            val endFraction = if (expand) 1f else 0f
            val pushDistance = calculateButtonsPushDistance(canShowAccountButton)

            searchRevealAnimator?.cancel()
            val animator = ValueAnimator.ofFloat(startFraction, endFraction)
            searchRevealAnimator = animator
            animator.duration = SEARCH_ANIMATION_MS
            animator.addUpdateListener { valueAnimator ->
                val fraction = valueAnimator.animatedValue as Float
                applySearchClipFraction(fraction)
                val translationX = -pushDistance * fraction
                addButton.translationX = translationX
                accountButton.translationX = translationX
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
                        accountButton.translationX = 0f
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

    private fun calculateButtonsPushDistance(canShowAccountButton: Boolean): Float {
        val addButton = binding.btnAddGroup
        val accountButton = binding.btnSelectAccount
        val rightMost = if (canShowAccountButton && accountButton.width > 0) {
            maxOf(addButton.right, accountButton.right)
        } else {
            addButton.right
        }
        val buffer = (resources.displayMetrics.density * 16f).toInt()
        return (rightMost + buffer).toFloat()
    }

    private fun updateSearchToggleIcon(isSearchVisible: Boolean) {
        if (isSearchVisible) {
            binding.ctcibToggleSearch.setIcon(R.drawable.ic_close_24)
            binding.ctcibToggleSearch.contentDescription = getString(R.string.close)
            return
        }
        binding.ctcibToggleSearch.setIcon(R.drawable.ic_search_24)
        binding.ctcibToggleSearch.contentDescription = getString(R.string.search_group_hint)
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
            showRingtoneSourcePicker(labelItem)
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
        viewModel.selectingGroup = labelItem
        pickAudioFileLauncher.launch(RingtoneFormatValidator.getMimeTypeFilter())
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
}
