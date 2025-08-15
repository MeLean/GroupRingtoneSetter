package com.milen.grounpringtonesetter.screens.picker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.customviews.dialog.ButtonData
import com.milen.grounpringtonesetter.customviews.dialog.showAlertDialog
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.SelectableContact
import com.milen.grounpringtonesetter.databinding.FragmentPickerScreenBinding
import com.milen.grounpringtonesetter.screens.picker.data.PickerResultData
import com.milen.grounpringtonesetter.screens.viewmodel.MainViewModel
import com.milen.grounpringtonesetter.screens.viewmodel.MainViewModelFactory
import com.milen.grounpringtonesetter.utils.changeMainTitle
import com.milen.grounpringtonesetter.utils.collectScoped
import com.milen.grounpringtonesetter.utils.handleLoading
import com.milen.grounpringtonesetter.utils.hideSoftInput
import com.milen.grounpringtonesetter.utils.manageVisibility
import kotlinx.coroutines.launch

internal class PickerScreenFragment : Fragment() {
    private lateinit var binding: FragmentPickerScreenBinding
    private val viewModel: MainViewModel by activityViewModels {
        MainViewModelFactory.provideFactory(requireActivity())
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

        collectScoped(viewModel.pickerUiState) { ui ->
            if (ui.shouldPop) {
                findNavController().popBackStack()
                return@collectScoped
            }
            binding.apply {
                changeMainTitle(getString(ui.titleId))
                crbDone.run {
                    setOnClickListener {
                        hideSoftInput()
                        ui.pikerResultData?.let { pikerData -> onResult(data = pikerData) }
                    }
                    this@run.isVisible = !ui.isLoading
                }

                ui.pikerResultData?.run {
                    crbResetRingtones.isVisible = false
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

        binding.abPicker.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                billing.state.collect { st ->
                    binding.abPicker.manageVisibility(st)

                }
            }
        }
    }

    private fun FragmentPickerScreenBinding.manageResetButton() {
        crbResetRingtones.apply {
            isVisible = true
            setOnClickListener {
                requireActivity().showAlertDialog(
                    titleResId = R.string.reset_all_ringtones,
                    message = getString(R.string.reset_all_ringtones_description),
                    confirmButtonData = ButtonData { viewModel.resetGroupRingtones() }
                )
            }
        }
    }

    private fun handleChangeName(data: PickerResultData.GroupNameChange) {
        binding.run {
            scvContacts.isVisible = false
            civNameInput.apply {
                this@apply.isVisible = true
                setText(data.newGroupName ?: data.labelItem.groupName)
                setCustomHint(getString(R.string.edit_group_name))
                setSoftDoneCLicked { onResult(data) }
            }
            noItemDisclaimer.isVisible = false
        }
    }

    private fun handleManageContacts(data: PickerResultData.ManageGroupContacts, loading: Boolean) {
        binding.run {
            civNameInput.isVisible = false
            scvContacts.isVisible = true
            scvContacts.submitContacts(
                data.allContacts.map { contact ->
                    SelectableContact.from(
                        contact = contact,
                        isSelected = data.selectedContacts.hasContact(contact)
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
            llAccountPickerHolder.isVisible = data.accountLists.size > 1

            accountPicker.apply {
                val adapter = ArrayAdapter(
                    context,
                    R.layout.custom_dropdown_holder,
                    data.accountLists.map { it.name }
                ).also { it.setDropDownViewResource(R.layout.custom_dropdown_item) }
                this.adapter = adapter
            }

            civNameInput.apply {
                this@apply.isVisible = true
                setText(data.groupName)
                setCustomHint(getString(R.string.enter_group_name))
                setSoftDoneCLicked { onResult(data.copy(pickedAccount = getDataOrNull(data))) }
            }
            noItemDisclaimer.isVisible = false
        }
    }

    private fun FragmentPickerScreenBinding.getDataOrNull(data: PickerResultData.ManageGroups) =
        if (accountPicker.selectedItemPosition != -1 && data.accountLists.isNotEmpty()) {
            data.accountLists[accountPicker.selectedItemPosition]
        } else null

    private fun onResult(data: PickerResultData) {
        when (data) {
            is PickerResultData.GroupNameChange ->
                viewModel.onPickerResult(data.copy(newGroupName = binding.civNameInput.getText()))
            is PickerResultData.ManageGroups ->
                viewModel.onPickerResult(data.copy(groupName = binding.civNameInput.getText()))
            is PickerResultData.ManageGroupContacts ->
                viewModel.onPickerResult(data.copy(selectedContacts = binding.scvContacts.selectedContacts))
            is PickerResultData.Canceled -> viewModel.onPickerResult(data)
        }
        findNavController().popBackStack()
    }
}

private fun List<Contact>.hasContact(contact: Contact): Boolean =
    this.any { it.id == contact.id && it.phone == contact.phone && it.name == contact.name }

