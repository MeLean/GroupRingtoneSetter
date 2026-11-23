package com.milen.grounpringtonesetter.ui.defaulttones

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.customviews.dialog.applyHomeDialogTheme
import com.milen.grounpringtonesetter.ui.RingtoneCompletion

/** Restores visible feedback across configuration changes; only user dismissal consumes the result. */
internal class DefaultToneCompletionDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
            .setTitle(R.string.info)
            .setMessage(requireArguments().getInt(MESSAGE_ID))
            .setPositiveButton(R.string.ok) { _, _ -> publishDismissal() }
            .create()

    override fun onStart() {
        super.onStart()
        (dialog as? AlertDialog)?.applyHomeDialogTheme(requireActivity())
    }

    override fun onCancel(dialog: DialogInterface) {
        publishDismissal()
        super.onCancel(dialog)
    }

    private fun publishDismissal() {
        parentFragmentManager.setFragmentResult(
            RESULT_KEY, bundleOf(COMPLETION_ID to requireArguments().getLong(COMPLETION_ID)),
        )
    }

    companion object {
        const val RESULT_KEY = "default_tone_completion_dismissed"
        const val COMPLETION_ID = "completion_id"
        private const val MESSAGE_ID = "message_id"
        private const val TAG = "default_tone_completion"

        fun show(host: Fragment, completion: RingtoneCompletion) {
            val manager = host.childFragmentManager
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            DefaultToneCompletionDialog().apply {
                arguments =
                    bundleOf(COMPLETION_ID to completion.id, MESSAGE_ID to completion.messageResId)
            }.showNow(manager, TAG)
        }
    }
}
