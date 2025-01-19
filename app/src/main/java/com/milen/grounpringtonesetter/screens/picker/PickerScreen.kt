package com.milen.grounpringtonesetter.screens.picker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.SelectableContact
import com.milen.grounpringtonesetter.databinding.FragmentPickerScreenBinding
import com.milen.grounpringtonesetter.screens.picker.data.PickerResultData
import com.milen.grounpringtonesetter.screens.viewmodel.MainViewModel
import com.milen.grounpringtonesetter.screens.viewmodel.MainViewModelFactory
import com.milen.grounpringtonesetter.utils.changeMainTitle
import com.milen.grounpringtonesetter.utils.collectScoped
import com.milen.grounpringtonesetter.utils.handleLoading
import com.milen.grounpringtonesetter.utils.hideSoftInput

class PickerScreenFragment : Fragment() {
    private lateinit var binding: FragmentPickerScreenBinding
    private val viewModel: MainViewModel by activityViewModels {
        MainViewModelFactory.provideFactory(requireActivity())
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

        collectScoped(viewModel.pickerUiState) {

            binding.apply {
                changeMainTitle(getString(it.titleId))

                crbDone.run {
                    setOnClickListener {
                        hideSoftInput()
                        it.pikerResultData?.let { pikerData -> onResult(data = pikerData) }
                    }
                    this@run.isVisible = !it.isLoading
                }

                it.pikerResultData?.run {
                    when (this) {
                        is PickerResultData.GroupNameChange -> handleChangeName(this)
                        is PickerResultData.ManageGroups -> handleSetName(this)
                        is PickerResultData.Canceled -> Unit
                        is PickerResultData.ManageGroupContacts ->
                            handleManageContacts(this, it.isLoading)
                    }
                }
            }

            handleLoading(it.isLoading)
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
            scvContacts.apply {
                this@apply.isVisible = true
            }

            scvContacts.submitContacts(
                data.allContacts.map {
                    SelectableContact.from(
                        contact = it,
                        isSelected = data.selectedContacts.contains(it)
                    )
                }.also {
                    noItemDisclaimer.isVisible = it.isEmpty() && loading.not()
                }
            )
        }
    }

    private fun handleSetName(data: PickerResultData.ManageGroups) {
        binding.run {
            scvContacts.isVisible = false
            crbRemoveRepeating.apply {
                this@apply.isVisible = true
                setOnClickListener { viewModel.uniqueLabels() }
            }

            civNameInput.apply {
                this@apply.isVisible = true
                setText(data.groupName)
                setCustomHint(getString(R.string.enter_group_name))
                setSoftDoneCLicked { onResult(data) }
            }
            noItemDisclaimer.isVisible = false
        }
    }


    private fun onResult(data: PickerResultData) {
        when (data) {
            is PickerResultData.GroupNameChange ->
                viewModel.onPickerResult(
                    data.copy(newGroupName = binding.civNameInput.getText())
                )

            is PickerResultData.ManageGroups ->
                viewModel.onPickerResult(
                    data.copy(groupName = binding.civNameInput.getText())
                )

            is PickerResultData.ManageGroupContacts ->
                viewModel.onPickerResult(
                    data.copy(
                        selectedContacts = binding.scvContacts.selectedContacts
                    )
                )

            is PickerResultData.Canceled -> viewModel.onPickerResult(data)
        }

        findNavController().popBackStack()
    }

}

