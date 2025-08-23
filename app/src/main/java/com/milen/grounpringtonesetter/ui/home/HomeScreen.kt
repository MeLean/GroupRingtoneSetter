package com.milen.grounpringtonesetter.ui.home

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
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
import com.milen.grounpringtonesetter.utils.areAllPermissionsGranted
import com.milen.grounpringtonesetter.utils.audioPermissionSdkBased
import com.milen.grounpringtonesetter.utils.changeMainTitle
import com.milen.grounpringtonesetter.utils.collectEventsIn
import com.milen.grounpringtonesetter.utils.collectStateIn
import com.milen.grounpringtonesetter.utils.getFileNameOrEmpty
import com.milen.grounpringtonesetter.utils.handleLoading
import com.milen.grounpringtonesetter.utils.log
import com.milen.grounpringtonesetter.utils.manageVisibility
import com.milen.grounpringtonesetter.utils.navigateSingleTop
import com.milen.grounpringtonesetter.utils.parcelableOrNull
import com.milen.grounpringtonesetter.utils.subscribeForConnectivityChanges

internal class HomeScreen : Fragment(), GroupsAdapter.GroupItemsInteractor {
    private lateinit var binding: FragmentHomeScreenBinding
    private lateinit var groupsAdapter: GroupsAdapter
    private val viewModel: HomeViewModel by activityViewModels {
        HomeViewModelFactory.provideFactory(requireActivity())
    }

    private lateinit var dialogHandler: DialogHandler

    private val permissions = mutableListOf(
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.WRITE_CONTACTS
    ).also { it.add(audioPermissionSdkBased()) }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        groupsAdapter = GroupsAdapter(this)

        checkPermissions()

        requireActivity().subscribeForConnectivityChanges { isOnline ->
            viewModel.onConnectionChanged(isOnline)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentHomeScreenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.apply {
            rwGroupItems.adapter = groupsAdapter
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
                    state.labelItems.isEmpty()
                            && !state.isLoading
                            && state.arePermissionsGranted

                btnAddGroup.apply {
                    isVisible = !state.isLoading
                    setOnClickListener {
                        viewModel.setUpGroupCreateRequest()
                    }
                }

                btnSelectAccount.apply {
                    isVisible = !state.isLoading && state.canChangeAccount
                    setOnClickListener { viewModel.onSelectAccountClicked() }
                }

                abHome.manageVisibility(state.entitlement)

                btnRemoveAds.apply {
                    isVisible = !state.isLoading && state.entitlement != EntitlementState.OWNED
                    setOnClickListener {
                        viewModel.startPurchase(requireActivity())
                    }
                }
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

    override fun onResume() {
        super.onResume()
        changeMainTitle(getString(R.string.app_name))
    }

    private fun checkPermissions() {
        when {
            requireContext().areAllPermissionsGranted(permissions = permissions) ->
                viewModel.onPermissionsGranted()
            else -> viewModel.onNoPermissions()
        }
    }

    override fun onManageContacts(labelItem: LabelItem): Unit =
        requireActivity().showAlertDialog(
            titleResId = R.string.manage_contacts_group_name,
            message = getString(R.string.manage_contacts_group_name_desc),
            cancelButtonData = ButtonData(R.string.cancel),
            confirmButtonData = ButtonData {
                viewModel.setUpContactsManaging(labelItem)
            }
        )

    override fun onEditName(labelItem: LabelItem): Unit =
        requireActivity().showAlertDialog(
            titleResId = R.string.edit_group_name,
            message = getString(R.string.edit_group_name_desc),
            cancelButtonData = ButtonData(R.string.cancel),
            confirmButtonData = ButtonData {
                viewModel.setUpGroupNameEditing(labelItem)
            }
        )

    override fun onGroupDelete(labelItem: LabelItem): Unit =
        requireActivity().showAlertDialog(
            titleResId = R.string.delete_group,
            message = getString(R.string.delete_group_desc),
            cancelButtonData = ButtonData(R.string.cancel),
            confirmButtonData = ButtonData {
                viewModel.onGroupDeleted(labelItem)
            }
        )

    override fun onChoseRingtoneIntent(labelItem: LabelItem) {
        if (requireContext().areAllPermissionsGranted(permissions = permissions)) {
            viewModel.selectingGroup = labelItem
            pickAudioFileLauncher.launch("audio/*")
        } else {
            viewModel.onNoPermissions()
        }
    }
}