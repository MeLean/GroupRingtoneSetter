package com.milen.grounpringtonesetter.ui.picker

import android.accounts.Account
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.os.bundleOf
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
import com.milen.grounpringtonesetter.customviews.dialog.DialogShower
import com.milen.grounpringtonesetter.customviews.dialog.showAlertDialog
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.SelectableContact
import com.milen.grounpringtonesetter.databinding.FragmentPickerScreenBinding
import com.milen.grounpringtonesetter.ui.picker.data.PickerMode
import com.milen.grounpringtonesetter.ui.picker.data.PickerResultData
import com.milen.grounpringtonesetter.ui.picker.viewmodel.PickerViewModel
import com.milen.grounpringtonesetter.ui.picker.viewmodel.PickerViewModelFactory
import com.milen.grounpringtonesetter.utils.changeMainTitle
import com.milen.grounpringtonesetter.utils.collectScoped
import com.milen.grounpringtonesetter.utils.handleLoading
import com.milen.grounpringtonesetter.utils.hideSoftInput
import com.milen.grounpringtonesetter.utils.manageVisibility
import com.milen.grounpringtonesetter.utils.parcelableArrayListOrThrow
import com.milen.grounpringtonesetter.utils.parcelableOrThrow
import kotlinx.coroutines.launch

internal class PickerScreenFragment : Fragment() {
    private lateinit var binding: FragmentPickerScreenBinding
    private lateinit var dialogShower: DialogShower

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

        when (mode) {
            PickerMode.RENAME -> {
                val group = requireArguments().parcelableOrThrow<LabelItem>(ARG_GROUP)
                viewModel.startRename(group)
            }

            PickerMode.MANAGE -> {
                val group = requireArguments().parcelableOrThrow<LabelItem>(ARG_GROUP)
                viewModel.startManageContacts(group)
            }

            PickerMode.CREATE -> {
                val accounts = requireArguments().parcelableArrayListOrThrow<Account>(ARG_ACCOUNTS)
                viewModel.startCreateGroup(accounts)
            }
        }

        // UI state
        collectScoped(viewModel.state) { ui ->
            binding.apply {
                changeMainTitle(getString(ui.titleId))
                crbDone.run {
                    setOnClickListener {
                        hideSoftInput()
                        ui.pikerResultData?.let { pikerData -> onResult(pikerData) }
                    }
                    isVisible = !ui.isLoading
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

        // One-shot events (Close)
        collectScoped(viewModel.events) { event ->
            when (event) {
                is PickerEvent.Close -> findNavController().popBackStack()
                is PickerEvent.DoneDialog -> showDoneDialog()
                is PickerEvent.ShowErrorById -> dialogShower.showErrorById(event.strRes)
                is PickerEvent.ShowErrorText -> dialogShower.showError(event.message)
                is PickerEvent.ShowInfoText -> dialogShower.showInfo(event.strRes)
            }
        }

        binding.abPicker.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                billing.state.collect { st ->
                    binding.abPicker.manageVisibility(st)
                }
            }
        }

        dialogShower = DialogShower(requireActivity())
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

            val adapter = ArrayAdapter(
                requireContext(),
                R.layout.custom_dropdown_holder,
                data.accountLists.map { it.name }
            ).also { it.setDropDownViewResource(R.layout.custom_dropdown_item) }
            accountPicker.adapter = adapter

            civNameInput.apply {
                isVisible = true
                setText(data.groupName)
                setCustomHint(getString(R.string.enter_group_name))
                setSoftDoneCLicked { onResult(data) }
            }
            noItemDisclaimer.isVisible = false
        }
    }

    private fun FragmentPickerScreenBinding.getDataOrNull(
        data: PickerResultData.ManageGroups,
    ): Account? =
        if (accountPicker.selectedItemPosition != -1 && data.accountLists.isNotEmpty())
            data.accountLists[accountPicker.selectedItemPosition]
        else
            null

    private fun onResult(data: PickerResultData) {
        when (data) {
            is PickerResultData.GroupNameChange -> {
                val newName = binding.civNameInput.getText()
                viewModel.confirmRename(data.labelItem, newName)
            }

            is PickerResultData.ManageGroups -> {
                val name = binding.civNameInput.getText()
                val account = binding.getDataOrNull(data)
                viewModel.confirmCreateGroup(name, account)
            }

            is PickerResultData.ManageGroupContacts -> {
                val chosen: List<Contact> = binding.scvContacts.selectedContacts
                viewModel.confirmManageContacts(data.group, chosen)
            }

            is PickerResultData.Canceled -> viewModel.cancel()
        }
    }

    private fun showDoneDialog() {
        requireActivity().showAlertDialog(
            titleResId = R.string.done,
            message = getString(R.string.everything_set),
            confirmButtonData = ButtonData(
                R.string.ok,
                onClick = { findNavController().popBackStack() }
            )
        )
    }

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_GROUP = "group"
        private const val ARG_ACCOUNTS = "accounts"

        fun argsForRename(group: LabelItem) = bundleOf(
            ARG_MODE to PickerMode.RENAME.name,
            ARG_GROUP to group
        )

        fun argsForManage(group: LabelItem) = bundleOf(
            ARG_MODE to PickerMode.MANAGE.name,
            ARG_GROUP to group
        )

        fun argsForCreate(accounts: List<Account>) = bundleOf(
            ARG_MODE to PickerMode.CREATE.name,
            ARG_GROUP to "",
            ARG_ACCOUNTS to ArrayList(accounts),
        )
    }
}


private fun List<Contact>.hasContact(contact: Contact): Boolean =
    any { it.id == contact.id && it.phone == contact.phone && it.name == contact.name }
