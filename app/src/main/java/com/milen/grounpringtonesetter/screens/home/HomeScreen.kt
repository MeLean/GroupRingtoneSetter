package com.milen.grounpringtonesetter.screens.home

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
import com.milen.billing.EntitlementState
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.customviews.dialog.ButtonData
import com.milen.grounpringtonesetter.customviews.dialog.showAlertDialog
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.databinding.FragmentHomeScreenBinding
import com.milen.grounpringtonesetter.screens.viewmodel.MainViewModel
import com.milen.grounpringtonesetter.screens.viewmodel.MainViewModelFactory
import com.milen.grounpringtonesetter.utils.areAllPermissionsGranted
import com.milen.grounpringtonesetter.utils.audioPermissionSdkBased
import com.milen.grounpringtonesetter.utils.changeMainTitle
import com.milen.grounpringtonesetter.utils.collectScoped
import com.milen.grounpringtonesetter.utils.getFileNameOrEmpty
import com.milen.grounpringtonesetter.utils.handleLoading
import com.milen.grounpringtonesetter.utils.log
import com.milen.grounpringtonesetter.utils.manageVisibility
import com.milen.grounpringtonesetter.utils.navigateAsRoot
import com.milen.grounpringtonesetter.utils.navigateSingleTop
import com.milen.grounpringtonesetter.utils.subscribeForConnectivityChanges

internal class HomeScreen : Fragment(), GroupsAdapter.GroupItemsInteractor {
    private lateinit var binding: FragmentHomeScreenBinding
    private lateinit var groupsAdapter: GroupsAdapter
    private val viewModel: MainViewModel by activityViewModels {
        MainViewModelFactory.provideFactory(requireActivity())
    }

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

        collectScoped(viewModel.state) { state ->
            handleLoading(state.isLoading)

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
                        viewModel.trackNoneFatal(e)
                        (e.localizedMessage ?: e.toString()).log()
                    }
                }

                noItemDisclaimer.isVisible = state.labelItems.isEmpty() && !state.isLoading
                btnManageGroups.isVisible = !state.isLoading
                btnDoTheMagic.isVisible = !state.isLoading

                abHome.manageVisibility(state.entitlement)

                when (state.entitlement) {
                    EntitlementState.OWNED -> {
                        btnDoTheMagic.apply {
                            setLabel(getString(R.string.do_the_magic))
                            setOnClickListener {
                                viewModel.onSetAllGroupsRingtones()
                            }
                        }
                    }

                    EntitlementState.NOT_OWNED, EntitlementState.UNKNOWN -> {
                        btnDoTheMagic.apply {
                            setLabel(getString(R.string.ad_free_forever))
                            setOnClickListener {
                                viewModel.startPurchase(requireActivity())
                            }
                        }
                    }
                }
            }
        }

        checkPermissions()

        collectScoped(viewModel.events) { event ->
            when (event) {
                HomeEvent.ConnectionLost -> findNavController().navigateSingleTop(R.id.noInternetFragment)
            }
        }

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

            btnManageGroups.setOnClickListener {
                viewModel.setUpGroupCreateRequest()
                findNavController().navigateAsRoot(R.id.pickerFragment)
            }
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
            confirmButtonData = ButtonData {
                viewModel.setUpContactsManaging(labelItem)
                findNavController().navigateSingleTop(R.id.pickerFragment)
            }
        )

    override fun onEditName(labelItem: LabelItem): Unit =
        requireActivity().showAlertDialog(
            titleResId = R.string.edit_group_name,
            message = getString(R.string.edit_group_name_desc),
            confirmButtonData = ButtonData {
                viewModel.setUpGroupNameEditing(labelItem)
                findNavController().navigateSingleTop(R.id.pickerFragment)
            }
        )

    override fun onGroupDelete(labelItem: LabelItem): Unit =
        requireActivity().showAlertDialog(
            titleResId = R.string.delete_group,
            message = getString(R.string.delete_group_desc),
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

    override fun onApplyRingtone(labelItem: LabelItem) {
        viewModel.onApplySingleRingtone(labelItem)
    }
}