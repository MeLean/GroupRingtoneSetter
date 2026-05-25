package com.milen.grounpringtonesetter.ui.accounts

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.customviews.dialog.applyHomeDialogTheme
import com.milen.grounpringtonesetter.data.sources.ContactSource
import com.milen.grounpringtonesetter.utils.parcelableArrayListOrEmpty
import com.milen.grounpringtonesetter.utils.parcelableOrNull

/**
 * Single-choice source picker.
 * Returns an explicit result for both confirm and cancel paths.
 */
internal class AccountSelectionDialogFragment : DialogFragment() {

    private lateinit var sources: ArrayList<ContactSource>
    private var selectedIndex: Int = NO_INDEX
    private var didSubmitResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sources = requireArguments().parcelableArrayListOrEmpty(ARG_SOURCES)
        val preselected: ContactSource? = requireArguments().parcelableOrNull(ARG_SOURCE_SELECTED)

        selectedIndex =
            savedInstanceState?.getInt(STATE_SELECTED_INDEX, NO_INDEX)
                ?.takeIf { it != NO_INDEX }
                ?: preselected
                    ?.let { sel -> sources.indexOfFirst { it.stableKey == sel.stableKey } } ?: NO_INDEX
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val labels = sources.map { it.displayLabel(requireContext()) }.toTypedArray()

        return AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
            .setTitle(R.string.pick_contact_source_contacts)
            .setSingleChoiceItems(labels, selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton(R.string.ok) { dialog, _ ->
                if (sources.isNotEmpty()) {
                    val index = selectedIndex.coerceIn(0, sources.lastIndex)
                    publishResult(
                        confirmed = true,
                        selected = sources[index]
                    )
                } else {
                    publishResult(confirmed = false)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                publishResult(confirmed = false)
                dialog.dismiss()
            }
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
            }
    }

    override fun onCancel(dialog: DialogInterface) {
        publishResult(confirmed = false)
        super.onCancel(dialog)
    }

    override fun onStart() {
        super.onStart()
        val hostActivity = activity ?: return
        (dialog as? AlertDialog)?.applyHomeDialogTheme(hostActivity)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_INDEX, selectedIndex)
    }

    private fun publishResult(
        confirmed: Boolean,
        selected: ContactSource? = null,
    ) {
        if (didSubmitResult) return
        didSubmitResult = true
        setFragmentResult(
            requestKey = RESULT_KEY,
            result = bundleOf(
                EXTRA_CONFIRMED to confirmed,
                EXTRA_SELECTED to selected
            )
        )
    }

    companion object {
        private const val TAG = "AccountSelectionDialogFragment"
        private const val ARG_SOURCES = "sources"
        private const val ARG_SOURCE_SELECTED = "selected"
        private const val STATE_SELECTED_INDEX = "selected_index"
        private const val NO_INDEX = -1

        const val RESULT_KEY = "AccountSelectionDialogFragment.result"
        const val EXTRA_CONFIRMED = "confirmed"
        const val EXTRA_SELECTED = "selected"

        internal fun show(host: Fragment, sources: Collection<ContactSource>, selected: ContactSource?) {
            val fm = host.parentFragmentManager
            val existing = fm.findFragmentByTag(TAG) as? AccountSelectionDialogFragment
            if (existing?.dialog?.isShowing == true || existing?.isAdded == true) return

            AccountSelectionDialogFragment().apply {
                arguments = bundleOf(
                    ARG_SOURCES to ArrayList(sources),
                    ARG_SOURCE_SELECTED to selected
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
