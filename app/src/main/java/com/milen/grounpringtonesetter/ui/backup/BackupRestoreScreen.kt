package com.milen.grounpringtonesetter.ui.backup

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.dialog.ButtonData
import com.milen.grounpringtonesetter.customviews.dialog.DialogHandler
import com.milen.grounpringtonesetter.customviews.dialog.applyHomeDialogTheme
import com.milen.grounpringtonesetter.customviews.dialog.showAlertDialog
import com.milen.grounpringtonesetter.databinding.DialogBackupExportProgressBinding
import com.milen.grounpringtonesetter.databinding.FragmentBackupRestoreBinding
import com.milen.grounpringtonesetter.ui.ScreenInfoProvider
import com.milen.grounpringtonesetter.utils.changeMainTitle
import com.milen.grounpringtonesetter.utils.collectEventsIn
import com.milen.grounpringtonesetter.utils.collectStateIn
import com.milen.grounpringtonesetter.utils.currentThemeAppearance
import com.milen.grounpringtonesetter.utils.handleLoading
import com.milen.grounpringtonesetter.utils.manageVisibility

internal class BackupRestoreScreen : Fragment(), ScreenInfoProvider {

    private lateinit var binding: FragmentBackupRestoreBinding
    private lateinit var dialogHandler: DialogHandler
    private var progressDialog: AlertDialog? = null
    private var progressBinding: DialogBackupExportProgressBinding? = null
    private var progressKind: BackupProgressKind? = null

    private val viewModel: BackupRestoreViewModel by viewModels {
        BackupRestoreViewModelFactory.provideFactory(requireActivity())
    }
    private val adsManager by lazy(LazyThreadSafetyMode.NONE) {
        (requireActivity().application as App).adsManager
    }
    private var currentEntitlement = EntitlementState.UNKNOWN
    private var canLoadAds = false

    private val createBackupDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(GRS_MIME_TYPE)) { uri ->
            viewModel.onBackupDocumentCreated(uri)
        }

    private val openBackupDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::onBackupDocumentPicked)
        }

    private val writeSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.onReturnedFromWriteSettings()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentBackupRestoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialogHandler = DialogHandler(requireActivity())
        binding.abBackupRestore.setPlacement("backup_restore_banner")

        binding.crbExportBackup.setOnClickListener {
            viewModel.onExportClicked()
        }
        binding.crbRestoreBackup.setOnClickListener {
            viewModel.onRestoreClicked()
        }
        binding.crbBackupRestoreRemoveAds.setOnClickListener {
            viewModel.startPurchase(requireActivity())
        }

        viewModel.state.collectStateIn(viewLifecycleOwner) { state ->
            currentEntitlement = state.entitlement
            handleLoading(state.isLoading)
            renderExportProgress(state.exportProgressPercent)
            renderRestoreProgress(state.restoreProgressPercent)
            renderState(state)
            renderBannerVisibility()
        }

        adsManager.canLoadAds.collectStateIn(viewLifecycleOwner) { canLoadAds ->
            this.canLoadAds = canLoadAds
            renderBannerVisibility()
        }

        viewModel.events.collectEventsIn(viewLifecycleOwner) { event ->
            when (event) {
                is BackupRestoreEvent.CreateBackupDocument -> {
                    createBackupDocumentLauncher.launch(event.fileName)
                }

                BackupRestoreEvent.OpenBackupDocument -> {
                    openBackupDocumentLauncher.launch(
                        arrayOf(
                            GRS_MIME_TYPE,
                            ZIP_MIME_TYPE,
                            X_ZIP_COMPRESSED_MIME_TYPE,
                            OCTET_STREAM_MIME_TYPE,
                            ANY_MIME_TYPE
                        )
                    )
                }

                is BackupRestoreEvent.ShowErrorById -> {
                    dismissProgressDialog()
                    dialogHandler.showErrorById(event.messageResId)
                }

                is BackupRestoreEvent.ShowInfoById -> {
                    dialogHandler.showInfo(event.messageResId)
                }

                is BackupRestoreEvent.ShowExportSummary -> {
                    showExportSummary(event)
                }

                is BackupRestoreEvent.ShowRestorePreview -> {
                    showRestorePreview(event)
                }

                is BackupRestoreEvent.ShowRestoreResult -> {
                    showRestoreResult(event)
                }

                is BackupRestoreEvent.OpenIntent -> {
                    openIntentSafely(event.intent)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        changeMainTitle(getString(R.string.backup_restore_title))
    }

    override fun onDestroyView() {
        dismissProgressDialog()
        super.onDestroyView()
    }

    private fun renderState(state: BackupRestoreState) {
        val isOwned = state.entitlement == EntitlementState.OWNED
        binding.llBackupRestoreLocked.isVisible = !isOwned
        binding.llBackupRestoreUnlocked.isVisible = isOwned
        binding.crbBackupRestoreRemoveAds.setButtonEnabled(!state.isPurchaseInProgress)
        binding.crbExportBackup.setButtonEnabled(isOwned && !state.isBusy)
        binding.crbRestoreBackup.setButtonEnabled(isOwned && !state.isBusy)
        if (state.isLoading) {
            binding.ctvBackupRestoreStatus.setText(R.string.backup_restore_processing_status)
        }
    }

    private fun renderBannerVisibility() {
        binding.abBackupRestore.manageVisibility(currentEntitlement, canLoadAds)
    }

    private fun renderExportProgress(percent: Int?) {
        renderProgress(percent, BackupProgressKind.EXPORT)
    }

    private fun renderRestoreProgress(percent: Int?) {
        renderProgress(percent, BackupProgressKind.RESTORE)
    }

    private fun renderProgress(percent: Int?, kind: BackupProgressKind) {
        if (percent == null) {
            if (progressKind != kind) return
            val wasShowing = progressDialog?.isShowing == true
            dismissProgressDialog()
            if (wasShowing) {
                binding.ctvBackupRestoreStatus.setText(R.string.backup_restore_ready_status)
            }
            return
        }

        val progress = percent.coerceIn(0, 100)
        val percentText = getString(R.string.backup_restore_export_progress_percent, progress)
        val statusText = when (kind) {
            BackupProgressKind.EXPORT -> getString(
                R.string.backup_restore_export_progress_status,
                progress
            )

            BackupProgressKind.RESTORE -> getString(
                R.string.backup_restore_restore_progress_status,
                progress
            )
        }
        val progressBinding = this.progressBinding
            ?.takeIf { progressKind == kind }
            ?: showProgressDialog(kind)
            ?: return

        progressBinding.pbBackupExportProgress.progress = progress
        progressBinding.pbBackupExportProgress.contentDescription = statusText
        progressBinding.ctvBackupExportProgressPercent.text = percentText
        progressBinding.ctvBackupExportProgressPercent.contentDescription = percentText
        binding.ctvBackupRestoreStatus.text = statusText
    }

    private fun showProgressDialog(kind: BackupProgressKind): DialogBackupExportProgressBinding? {
        val activity = activity ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        if (progressDialog?.isShowing == true) {
            dismissProgressDialog()
        }

        val progressBinding = DialogBackupExportProgressBinding.inflate(layoutInflater)
        progressBinding.ctvBackupExportProgressMessage.setText(kind.messageResId)
        progressBinding.crbBackupProgressCancel.isVisible = kind.isCancellable
        progressBinding.crbBackupProgressCancel.setOnClickListener {
            when (kind) {
                BackupProgressKind.EXPORT -> viewModel.onExportCancelled()
                BackupProgressKind.RESTORE -> viewModel.onRestoreCancelled()
            }
            progressBinding.crbBackupProgressCancel.setButtonEnabled(false)
        }
        val builder = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(kind.titleResId)
            .setView(progressBinding.root)
        val dialog = builder.create()

        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            progressDialog = null
            this.progressBinding = null
            progressKind = null
        }

        val showResult = runCatching {
            dialog.show()
            dialog.applyHomeDialogTheme(activity)
            applyProgressTheme(progressBinding)
        }
        if (showResult.isFailure) {
            if (dialog.isShowing) {
                dialog.dismiss()
            }
            return null
        }

        progressDialog = dialog
        this.progressBinding = progressBinding
        progressKind = kind
        return progressBinding
    }

    private fun applyProgressTheme(progressBinding: DialogBackupExportProgressBinding) {
        val themeAppearance = requireContext().currentThemeAppearance()
        progressBinding.pbBackupExportProgress.progressTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), themeAppearance.actionButtonBackgroundColorRes)
        )
        progressBinding.pbBackupExportProgress.progressBackgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), themeAppearance.dialogBorderColorRes)
        )
    }

    private fun dismissProgressDialog() {
        val dialog = progressDialog
        progressDialog = null
        progressBinding = null
        progressKind = null
        if (dialog?.isShowing == true) {
            dialog.dismiss()
        }
    }

    private fun showExportSummary(event: BackupRestoreEvent.ShowExportSummary) {
        val message = BackupRestoreSummaryText.buildExportSummary(
            format = getString(R.string.backup_restore_export_summary),
            result = event.result
        )
        binding.ctvBackupRestoreStatus.text = message
        requireActivity().showAlertDialog(
            titleResId = R.string.backup_restore_export_complete_title,
            message = message,
            confirmButtonData = ButtonData(R.string.ok)
        )
    }

    private fun showRestorePreview(event: BackupRestoreEvent.ShowRestorePreview) {
        val message = BackupRestoreSummaryText.buildRestorePreview(
            format = getString(R.string.backup_restore_preview_summary),
            groupToneLineFormat = getString(R.string.backup_restore_preview_group_tone_line),
            preview = event.preview,
            systemSettingsPermissionNote = getString(
                R.string.backup_restore_system_settings_permission_note
            )
        )
        binding.ctvBackupRestoreStatus.text = message
        requireActivity().showAlertDialog(
            titleResId = R.string.backup_restore_preview_title,
            message = message,
            cancelButtonData = ButtonData(R.string.cancel) {
                viewModel.onRestoreCancelled()
            },
            confirmButtonData = ButtonData(R.string.confirm) {
                viewModel.onRestoreConfirmed()
            }
        )
    }

    private fun showRestoreResult(event: BackupRestoreEvent.ShowRestoreResult) {
        parentFragmentManager.setFragmentResult(
            BACKUP_RESTORE_RESULT_KEY,
            bundleOf(BACKUP_RESTORE_EXTRA_RESTORE_COMPLETED to true)
        )
        val message = BackupRestoreSummaryText.buildRestoreResult(
            format = getString(R.string.backup_restore_result_summary),
            result = event.result
        )
        binding.ctvBackupRestoreStatus.text = message
        requireActivity().showAlertDialog(
            titleResId = R.string.backup_restore_complete_title,
            message = message,
            confirmButtonData = ButtonData(R.string.ok)
        )
    }

    private fun openIntentSafely(intent: Intent) {
        val canResolve = intent.resolveActivity(requireContext().packageManager) != null
        if (!canResolve) {
            dialogHandler.showErrorById(R.string.something_went_wrong)
            return
        }
        try {
            if (intent.action == Settings.ACTION_MANAGE_WRITE_SETTINGS) {
                writeSettingsLauncher.launch(intent)
            } else {
                val result = requireActivity().startActivityIfNeeded(intent, REQUEST_OPEN_INTENT)
                if (!result) {
                    requireActivity().startActivity(intent)
                }
            }
        } catch (_: ActivityNotFoundException) {
            dialogHandler.showErrorById(R.string.something_went_wrong)
        } catch (_: SecurityException) {
            dialogHandler.showErrorById(R.string.something_went_wrong)
        }
    }

    override fun getScreenInfoMessageResId(): Int = R.string.backup_restore_info_text

    override fun getToolbarInfoMessageResId(): Int = R.string.backup_restore_info_text

    private companion object {
        const val GRS_MIME_TYPE = "application/grs+zip"
        const val ZIP_MIME_TYPE = "application/zip"
        const val X_ZIP_COMPRESSED_MIME_TYPE = "application/x-zip-compressed"
        const val OCTET_STREAM_MIME_TYPE = "application/octet-stream"
        const val ANY_MIME_TYPE = "*/*"
        const val REQUEST_OPEN_INTENT = 8401
    }

    private enum class BackupProgressKind(
        @param:StringRes val titleResId: Int,
        @param:StringRes val messageResId: Int,
        val isCancellable: Boolean,
    ) {
        EXPORT(
            titleResId = R.string.backup_restore_export_progress_title,
            messageResId = R.string.backup_restore_export_progress_message,
            isCancellable = true
        ),
        RESTORE(
            titleResId = R.string.backup_restore_restore_progress_title,
            messageResId = R.string.backup_restore_restore_progress_message,
            isCancellable = true
        )
    }
}
