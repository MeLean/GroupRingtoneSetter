package com.milen.grounpringtonesetter.ui.accounts

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import com.milen.grounpringtonesetter.R

class AccountSelectionDialogFragment : DialogFragment() {

    private lateinit var accounts: ArrayList<String>
    private lateinit var checked: BooleanArray

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accounts = requireArguments().getStringArrayList(ARG_ACCOUNTS) ?: arrayListOf()

        // ✅ Default: ALL preselected. If we rotate, restore the previous state.
        checked = savedInstanceState?.getBooleanArray(STATE_CHECKED)
            ?: BooleanArray(accounts.size) { true }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBooleanArray(STATE_CHECKED, checked)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val items = accounts.map(::labelOf).toTypedArray()

        val dlg = AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
            .setTitle(R.string.pick_account_contacts)
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
                (dialog as? AlertDialog)
                    ?.getButton(AlertDialog.BUTTON_POSITIVE)
                    ?.isEnabled = checked.any { it }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // ✅ If the user never changes anything, this still returns ALL accounts,
                // because all checkboxes start true.
                val selected = ArrayList<String>().apply {
                    accounts.forEachIndexed { i, raw -> if (checked[i]) add(raw) }
                }
                if (selected.isEmpty()) return@setPositiveButton
                setFragmentResult(RESULT_KEY, bundleOf(EXTRA_SELECTED to selected))
            }
            .create().apply {
                setOnShowListener {
                    // With all preselected, this starts enabled.
                    getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checked.any { it }
                }
                setCanceledOnTouchOutside(false)
            }

        isCancelable = false
        return dlg
    }

    private fun labelOf(raw: String): String {
        val i = raw.indexOf(':')
        return if (i >= 0 && i < raw.length - 1) raw.substring(i + 1) else raw
    }

    companion object {
        private const val TAG = "AccountSelectionDialogFragment"
        private const val ARG_ACCOUNTS = "accounts"
        private const val STATE_CHECKED = "state_checked"

        const val RESULT_KEY = "AccountSelectionDialogFragment_result"
        const val EXTRA_SELECTED = "selected_accounts"

        /**
         * Show the dialog with **all accounts preselected**.
         * If an instance already exists, dismiss it first to avoid duplicates.
         */
        fun show(host: Fragment, accounts: List<String>) {
            val fm = host.parentFragmentManager

            (fm.findFragmentByTag(TAG) as? DialogFragment)?.let { existing ->
                if (existing.dialog?.isShowing == true || existing.isAdded) {
                    if (fm.isStateSaved) existing.dismissAllowingStateLoss() else existing.dismiss()
                }
            }

            val dlg = AccountSelectionDialogFragment().apply {
                arguments = bundleOf(ARG_ACCOUNTS to ArrayList(accounts))
            }

            if (fm.isStateSaved) {
                fm.beginTransaction().add(dlg, TAG).commitAllowingStateLoss()
            } else {
                dlg.show(fm, TAG)
            }
        }
    }
}
