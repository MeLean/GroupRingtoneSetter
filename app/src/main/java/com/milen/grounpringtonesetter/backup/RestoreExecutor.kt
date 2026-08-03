package com.milen.grounpringtonesetter.backup

import android.content.Context
import android.net.Uri
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.local.LocalLabelsStore
import com.milen.grounpringtonesetter.data.sources.ContactSource
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneManager
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.MediaStoreToneImporter
import com.milen.grounpringtonesetter.utils.Telemetry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

internal class RestoreExecutor(
    private val context: Context,
    private val contactsHelper: ContactsHelper,
    private val localLabelsStore: LocalLabelsStore,
    private val defaultToneManager: DeviceDefaultToneManager,
    private val toneImporter: MediaStoreToneImporter = MediaStoreToneImporter(),
    private val tracker: Telemetry,
) {
    suspend fun execute(
        source: ContactSource,
        plan: RestorePlan,
        assetBytesByPath: Map<String, ByteArray>,
        onProgress: (Int) -> Unit = {},
    ): RestoreExecutionResult {
        val coroutineContext = currentCoroutineContext()
        val canWriteSystemSettings =
            plan.defaultToneCount <= 0 || defaultToneManager.canWriteSystemSettings()
        val skippedDefaultToneCount = RestoreDefaultTonePermissionPolicy.skippedDefaultToneCount(
            defaultToneCount = plan.defaultToneCount,
            canWriteSystemSettings = canWriteSystemSettings
        )
        val tonesToImport = plan.snapshot.tones.filter { tone ->
            tone.id in plan.neededToneIds(canWriteSystemSettings)
        }
        val progress = RestoreExecutionProgress(
            totalSteps = tonesToImport.size + plan.groupTones.size + plan.defaultToneCount,
            onProgress = onProgress
        )
        onProgress(0)

        val toneUriById = importToneAssets(
            tones = tonesToImport,
            assetBytesByPath = assetBytesByPath,
            ensureActive = { coroutineContext.ensureActive() },
            onToneProcessed = progress::completeStep
        )
        var groupsProcessed = 0
        var contactsUpdated = 0
        var defaultTonesApplied = 0
        var missingGroups = 0
        var failedTones = tonesToImport.count { tone -> toneUriById[tone.id] == null }

        if (skippedDefaultToneCount > 0) {
            failedTones += skippedDefaultToneCount
            progress.completeSteps(skippedDefaultToneCount)
            tracker.trackEvent(
                "grs_restore_default_tones_skipped_write_settings_missing",
                mapOf("count" to skippedDefaultToneCount.toString())
            )
        } else {
            plan.snapshot.defaultTones.forEach { defaultTone ->
                coroutineContext.ensureActive()
                val uri = toneUriById[defaultTone.toneId]
                if (uri == null) {
                    failedTones += 1
                    progress.completeStep()
                    return@forEach
                }
                val applied = defaultToneManager.setDefaultTone(defaultTone.type, uri).isSuccess
                if (applied) {
                    defaultTonesApplied += 1
                } else {
                    failedTones += 1
                }
                progress.completeStep()
            }
        }

        val groupsByName = loadCurrentGroups(source).groupBy { group -> group.groupName }
        plan.groupTones.forEach { groupTone ->
            coroutineContext.ensureActive()
            val matchingGroups = groupsByName[groupTone.groupName].orEmpty()
            val uri = toneUriById[groupTone.toneId]
            if (matchingGroups.isEmpty()) {
                missingGroups += 1
                progress.completeStep()
                return@forEach
            }
            if (uri == null) {
                failedTones += 1
                progress.completeStep()
                return@forEach
            }
            matchingGroups.forEach { group ->
                val contacts = group.contacts.distinctBy { contact -> contact.id }
                val applied = runCatching {
                    contactsHelper.setRingtoneToLabelContacts(
                        labelContacts = contacts,
                        newRingtoneUriStr = uri.toString()
                    ).count { it.isSuccessful }
                }.onFailure { tracker.trackError(it) }
                    .getOrDefault(0)
                failedTones += (contacts.size - applied).coerceAtLeast(0)
                contactsUpdated += applied
            }
            groupsProcessed += matchingGroups.size
            progress.completeStep()
        }
        onProgress(100)

        return RestoreExecutionResult(
            groupsProcessed = groupsProcessed,
            contactsUpdated = contactsUpdated,
            defaultTonesApplied = defaultTonesApplied,
            missingGroups = missingGroups,
            failedTones = failedTones
        )
    }

    private suspend fun loadCurrentGroups(source: ContactSource): List<LabelItem> =
        when (source) {
            is ContactSource.CloudAccount -> contactsHelper.getAllLabelItemsForAccounts(source.account)
            ContactSource.OnDevice -> loadOnDeviceGroups()
        }

    private suspend fun loadOnDeviceGroups(): List<LabelItem> {
        val document = localLabelsStore.read()
        if (document.labels.isEmpty()) return emptyList()
        val contactsByLookupKey = contactsHelper.getContactsByLookupKeys(
            document.labels.flatMap { label -> label.members.map { it.lookupKey } }
        ).associateBy { it.lookupKey }

        return document.labels.map { label ->
            LabelItem(
                id = label.id,
                groupName = label.name,
                contacts = label.members.mapNotNull { member -> contactsByLookupKey[member.lookupKey] },
                ringtoneUriList = emptyList(),
                ringtoneFileName = ""
            )
        }
    }

    private fun importToneAssets(
        tones: List<GrsToneSnapshot>,
        assetBytesByPath: Map<String, ByteArray>,
        ensureActive: () -> Unit,
        onToneProcessed: () -> Unit,
    ): Map<String, Uri> {
        val result = linkedMapOf<String, Uri>()
        tones.forEach { tone ->
            ensureActive()
            val imported = assetBytesByPath[tone.archivePath]
                ?.let { bytes -> importToneAsset(tone, bytes) }
            if (imported != null) {
                result[tone.id] = imported
            }
            onToneProcessed()
        }
        return result
    }

    private fun importToneAsset(
        tone: GrsToneSnapshot,
        bytes: ByteArray,
    ): Uri? {
        val category = tone.category.toMediaStoreCategory()
        toneImporter.findExistingToneUri(
            context = context,
            displayName = tone.displayName,
            category = category
        )?.let { return it }

        val tempFile = File.createTempFile("grs_restore_", "_tone", context.cacheDir)
        return runCatching {
            tempFile.outputStream().use { output -> output.write(bytes) }
            toneImporter.importToneWithMetadata(
                context = context,
                sourceUri = Uri.fromFile(tempFile),
                category = category,
                desiredDisplayName = tone.displayName,
                reuseExistingByName = true
            ).getOrThrow().uri
        }.onFailure { tracker.trackError(it) }
            .getOrNull()
            .also { tempFile.delete() }
    }

    private fun RestorePlan.neededToneIds(
        canWriteSystemSettings: Boolean,
    ): Set<String> {
        val toneIds = linkedSetOf<String>()
        groupTones.mapTo(toneIds) { groupTone -> groupTone.toneId }
        if (canWriteSystemSettings) {
            snapshot.defaultTones.mapTo(toneIds) { defaultTone -> defaultTone.toneId }
        }
        return toneIds
    }

    private class RestoreExecutionProgress(
        totalSteps: Int,
        private val onProgress: (Int) -> Unit,
    ) {
        private val normalizedTotalSteps = totalSteps.coerceAtLeast(1)
        private var completedSteps = 0
        private var lastPercent = -1

        fun completeStep() {
            completeSteps(1)
        }

        fun completeSteps(count: Int) {
            if (count <= 0) return
            completedSteps += count
            val percent = ((completedSteps.coerceAtMost(normalizedTotalSteps) * 100) / normalizedTotalSteps)
                .coerceIn(0, 100)
            if (percent <= lastPercent) return
            lastPercent = percent
            onProgress(percent)
        }
    }
}
