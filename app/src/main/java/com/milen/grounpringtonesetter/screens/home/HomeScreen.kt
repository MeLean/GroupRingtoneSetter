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
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.customviews.dialog.ButtonData
import com.milen.grounpringtonesetter.customviews.dialog.showAlertDialog
import com.milen.grounpringtonesetter.data.GroupItem
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
import com.milen.grounpringtonesetter.utils.navigateSingleTop

class HomeScreen : Fragment(), GroupsAdapter.GroupItemsInteractor {
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

        collectScoped(viewModel.homeUiState) {
            handleLoading(it.isLoading)

            when {
                it.arePermissionsGranted.not() -> requestMultiplePermissions.launch(permissions.toTypedArray())

                else -> groupsAdapter.submitList(it.groupItems)
            }

            binding.apply {
                it.scrollToPosition?.let { position ->
                    rwGroupItems.smoothScrollToPosition(position)
                }

                noItemDisclaimer.isVisible = it.groupItems.isEmpty() && it.isLoading.not()
            }
        }

        checkPermissions()
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

        binding.rwGroupItems.adapter = groupsAdapter
        binding.btnDoTheMagic.setOnClickListener {
            viewModel.onSetRingtones()
        }

        binding.btnAddGroup.setOnClickListener {
            viewModel.setUpGroupCreateRequest().also {
                findNavController().navigateSingleTop(R.id.pickerFragment)
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

    override fun onManageContacts(groupItem: GroupItem): Unit =
        requireContext().showAlertDialog(
            titleResId = R.string.manage_contacts_group_name,
            message = getString(R.string.manage_contacts_group_name_desc),
            confirmButtonData = ButtonData {
                viewModel.setUpContactsManaging(groupItem)
                    .also { findNavController().navigateSingleTop(R.id.pickerFragment) }
            }
        )


    override fun onEditName(groupItem: GroupItem): Unit =
        requireContext().showAlertDialog(
            titleResId = R.string.edit_group_name,
            message = getString(R.string.edit_group_name_desc),
            confirmButtonData = ButtonData {
                viewModel.setUpGroupNameEditing(groupItem)
                    .also { findNavController().navigateSingleTop(R.id.pickerFragment) }
            }
        )

    override fun onGroupDelete(groupItem: GroupItem): Unit =
        requireContext().showAlertDialog(
            titleResId = R.string.delete_group,
            message = getString(R.string.delete_group_desc),
            confirmButtonData = ButtonData {
                viewModel.onGroupDeleted(groupItem)
            }
        )

    override fun onChoseRingtoneIntent(groupItem: GroupItem) {
        when {
            requireContext().areAllPermissionsGranted(permissions = permissions) -> {
                viewModel.selectingGroup = groupItem
                pickAudioFileLauncher.launch("audio/*")
            }

            else -> viewModel.onNoPermissions()
        }
    }
}