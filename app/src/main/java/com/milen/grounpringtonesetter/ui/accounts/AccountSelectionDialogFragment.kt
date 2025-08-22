package com.milen.grounpringtonesetter.ui.accounts

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.accounts.AccountId
import com.milen.grounpringtonesetter.data.accounts.AccountsResolver

/**
 * Single-choice account picker.
 * Returns a result ONLY when the positive button is clicked.
 */
class AccountSelectionDialogFragment : DialogFragment() {

    private lateinit var accounts: ArrayList<String>
    private var accountSelected: String? = null
    private var selectedIndex: Int = NO_INDEX

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accounts = requireArguments().getStringArrayList(ARG_ACCOUNTS) ?: arrayListOf()
        accountSelected = requireArguments().getString(ARG_ACCOUNT_SELECTED)

        selectedIndex =
            savedInstanceState?.getInt(STATE_SELECTED_INDEX, NO_INDEX)
                .takeIf { it != NO_INDEX }
                ?: accounts.indexOf(accountSelected.orEmpty())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val labels = accounts.map { AccountsResolver.labelOf(it) }.toTypedArray()

        return AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
            .setTitle(R.string.pick_account_contacts)
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                // Only update the in-memory selection; DO NOT return result here
                selectedIndex = which.coerceIn(0, accounts.lastIndex)
            }
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                // Return the chosen account ONLY on positive click
                if (accounts.isNotEmpty()) {
                    val index = selectedIndex.coerceIn(0, accounts.lastIndex)
                    val chosen = arrayListOf(accounts[index])
                    setFragmentResult(RESULT_KEY, bundleOf(EXTRA_SELECTED to chosen))
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null) // No result on cancel
            .create()
            .apply { setCanceledOnTouchOutside(false) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_INDEX, selectedIndex)
    }

    companion object {
        private const val TAG = "AccountSelectionDialogFragment"
        private const val ARG_ACCOUNTS = "accounts"
        private const val ARG_ACCOUNT_SELECTED = "selected"
        private const val STATE_SELECTED_INDEX = "selected_index"
        private const val NO_INDEX = -1

        const val RESULT_KEY = "AccountSelectionDialogFragment.result"
        const val EXTRA_SELECTED = "selected" // ArrayList<String> with one "type:name"

        fun show(host: Fragment, accounts: Collection<String>, selected: AccountId?) {
            val fm = host.parentFragmentManager
            val existing = fm.findFragmentByTag(TAG) as? AccountSelectionDialogFragment
            if (existing?.dialog?.isShowing == true || existing?.isAdded == true) return

            AccountSelectionDialogFragment().apply {
                arguments = bundleOf(
                    ARG_ACCOUNTS to ArrayList(accounts),
                    ARG_ACCOUNT_SELECTED to selected?.name
                )
            }.also { dlg ->
                if (fm.isStateSaved) {
                    fm.beginTransaction().add(dlg, TAG).commitAllowingStateLoss()
                } else {
                    dlg.show(fm, TAG)
                }
            }
        }
    }
}
