package com.milen.grounpringtonesetter.ui.picker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.customviews.dialog.ButtonData
import com.milen.grounpringtonesetter.customviews.dialog.DialogHandler
import com.milen.grounpringtonesetter.customviews.dialog.showAlertDialog
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.SelectableContact
import com.milen.grounpringtonesetter.databinding.FragmentPickerScreenBinding
import com.milen.grounpringtonesetter.ui.picker.data.PickerMode
import com.milen.grounpringtonesetter.ui.picker.data.PickerResultData
import com.milen.grounpringtonesetter.ui.picker.viewmodel.PickerViewModel
import com.milen.grounpringtonesetter.ui.picker.viewmodel.PickerViewModelFactory
import com.milen.grounpringtonesetter.utils.changeMainTitle
import com.milen.grounpringtonesetter.utils.collectEventsIn
import com.milen.grounpringtonesetter.utils.collectStateIn
import com.milen.grounpringtonesetter.utils.handleLoading
import com.milen.grounpringtonesetter.utils.hideSoftInput
import com.milen.grounpringtonesetter.utils.manageVisibility
import com.milen.grounpringtonesetter.utils.parcelableOrThrow

internal class PickerScreenFragment : Fragment() {
    private lateinit var binding: FragmentPickerScreenBinding
    private lateinit var dialogHandler: DialogHandler

    private val viewModel: PickerViewModel by activityViewModels {
        PickerViewModelFactory.provideFactory(requireActivity())
    }

    private val billing by lazy(LazyThreadSafetyMode.NONE) {
        (requireActivity().application as App).billingManager
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentPickerScreenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mode = requireArguments().getString(ARG_MODE)?.let(PickerMode::valueOf)
            ?: error("Missing picker mode")

        if (savedInstanceState == null || viewModel.state.value.pikerResultData == null) {
            when (mode) {
                PickerMode.RENAME -> {
                    val group = requireArguments().parcelableOrThrow<LabelItem>(ARG_GROUP)
                    viewModel.startRename(group)
                }

                PickerMode.MANAGE -> {
                    val group = requireArguments().parcelableOrThrow<LabelItem>(ARG_GROUP)
                    viewModel.startManageContacts(group)
                }

                PickerMode.CREATE -> viewModel.startCreateGroup()
            }
        }

        viewModel.state.collectStateIn(viewLifecycleOwner) { ui ->
            binding.apply {
                changeMainTitle(getString(ui.titleId))

                // Only Done triggers a write:
                crbDone.run {
                    setOnClickListener {
                        hideSoftInput()
                        ui.pikerResultData?.let { pikerData -> onResult(pikerData) }
                    }
                    isVisible = !ui.isLoading
                }

                ui.pikerResultData?.run {
                    when (this) {
                        is PickerResultData.ManageGroups -> handleSetName(this).also { manageResetButton() }
                        is PickerResultData.GroupNameChange -> handleChangeName(this)
                        is PickerResultData.Canceled -> Unit
                        is PickerResultData.ManageGroupContacts -> handleManageContacts(
                            this,
                            ui.isLoading
                        )
                    }
                }
            }
            handleLoading(ui.isLoading)
        }

        viewModel.events.collectEventsIn(viewLifecycleOwner) { event ->
            when (event) {
                is PickerEvent.Close -> findNavController().popBackStack()
                is PickerEvent.DoneDialog -> showDoneDialog()
                is PickerEvent.ShowErrorById -> dialogHandler.showErrorById(event.strRes)
                is PickerEvent.ShowErrorText -> dialogHandler.showError(event.message)
                is PickerEvent.ShowInfoText -> dialogHandler.showInfo(event.strRes)
            }
        }

        binding.adBannerPicker.isVisible = false
        billing.state.collectStateIn(viewLifecycleOwner) { st ->
            binding.adBannerPicker.manageVisibility(st)
        }

        dialogHandler = DialogHandler(requireActivity())
    }

    private fun FragmentPickerScreenBinding.manageResetButton() {
        crbResetRingtones.apply {
            isVisible = true
            setOnClickListener {
                requireActivity().showAlertDialog(
                    titleResId = R.string.reset_all_ringtones,
                    message = getString(R.string.reset_all_ringtones_description),
                    cancelButtonData = ButtonData(R.string.cancel),
                    confirmButtonData = ButtonData { viewModel.resetGroupRingtones() }
                )
            }
        }
    }

    private fun handleChangeName(data: PickerResultData.GroupNameChange) {
        binding.run {
            scvContacts.isVisible = false
            civNameInput.apply {
                isVisible = true
                setText(data.newGroupName ?: data.labelItem.groupName)
                setCustomHint(getString(R.string.edit_group_name))
                setSoftDoneCLicked { onResult(data) }
            }
            noItemDisclaimer.isVisible = false
        }
    }

    private fun handleManageContacts(
        data: PickerResultData.ManageGroupContacts,
        loading: Boolean,
    ) {
        binding.run {
            civNameInput.isVisible = false
            scvContacts.isVisible = true

            scvContacts.setOnCheckedChangeListener { list ->
                viewModel.updateManageSelection(list)
            }

            val selectedIds = data.selectedContacts.map { it.id }.toHashSet()
            scvContacts.submitContacts(
                data.allContacts.map { contact ->
                    SelectableContact.from(
                        contact = contact,
                        isSelected = (contact.id in selectedIds)
                    ).also {
                        noItemDisclaimer.isVisible = data.allContacts.isEmpty() && loading.not()
                    }
                }
            )
        }
    }
    private fun handleSetName(data: PickerResultData.ManageGroups) {
        binding.run {
            scvContacts.isVisible = false
            civNameInput.apply {
                isVisible = true
                setText(data.groupName)
                setCustomHint(getString(R.string.enter_group_name))
                setSoftDoneCLicked { onResult(data) }
            }
            noItemDisclaimer.isVisible = false
        }
    }

    /** Only Done calls the VM writes. */
    private fun onResult(data: PickerResultData) {
        when (data) {
            is PickerResultData.GroupNameChange -> {
                val newName = binding.civNameInput.getText()
                viewModel.confirmRename(data.labelItem, newName)
            }
            is PickerResultData.ManageGroups -> {
                val name = binding.civNameInput.getText()
                viewModel.confirmCreateGroup(name)
            }
            is PickerResultData.ManageGroupContacts -> {
                viewModel.confirmManageContacts(data.group)
            }
            is PickerResultData.Canceled -> viewModel.close()
        }
    }

    private fun showDoneDialog() {
        requireActivity().showAlertDialog(
            titleResId = R.string.done,
            message = getString(R.string.everything_set),
            confirmButtonData = ButtonData(
                R.string.ok,
                onClick = { viewModel.close() } // emits Close event
            )
        )
    }

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_GROUP = "group"

        fun argsForRename(group: LabelItem) = bundleOf(
            ARG_MODE to PickerMode.RENAME.name,
            ARG_GROUP to group
        )

        fun argsForManage(group: LabelItem) = bundleOf(
            ARG_MODE to PickerMode.MANAGE.name,
            ARG_GROUP to group
        )

        fun argsForCreate() = bundleOf(
            ARG_MODE to PickerMode.CREATE.name,
        )
    }
}