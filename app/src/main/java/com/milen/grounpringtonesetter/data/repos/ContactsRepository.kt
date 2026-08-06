package com.milen.grounpringtonesetter.data.repos

import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.LabelStorageKind
import com.milen.grounpringtonesetter.data.exceptions.isContactsPermissionFailure
import com.milen.grounpringtonesetter.data.local.LocalContactLabelMirror
import com.milen.grounpringtonesetter.data.local.LocalLabelDocument
import com.milen.grounpringtonesetter.data.local.LocalLabelsStore
import com.milen.grounpringtonesetter.data.local.LocalStoredLabel
import com.milen.grounpringtonesetter.data.local.LocalStoredLabelMember
import com.milen.grounpringtonesetter.data.local.MirroredLocalLabelAssignment
import com.milen.grounpringtonesetter.data.local.recoverLocalLabels
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.data.sources.ContactSource
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import com.milen.grounpringtonesetter.utils.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class RingtoneChoiceOption(
    val uri: String,
    val displayName: String,
)

internal data class GroupReassignmentValidation(
    val allowed: List<Contact>,
    val blocked: List<Contact>,
)

internal interface ContactsRepository {
    val labelsFlow: StateFlow<List<LabelItem>>
    val allContacts: StateFlow<List<Contact>?>

    suspend fun loadAccountLabels()

    suspend fun loadAccountLabelsShallow()

    suspend fun enrichRingtonesForCurrentLabels(batchSize: Int = 100)

    suspend fun setGroupRingtone(group: LabelItem, uriStr: String, fileName: String)

    suspend fun refreshAllPhoneContacts()

    suspend fun createGroup(name: String)
    suspend fun renameGroup(group: LabelItem, newName: String)
    suspend fun deleteGroup(group: LabelItem)

    suspend fun updateGroupMembers(
        group: LabelItem,
        newSelected: List<Contact>,
        oldSelected: List<Contact>,
        ringtoneForNewContactsUri: String?,
    )

    suspend fun validateGroupReassignment(
        group: LabelItem,
        candidates: List<Contact>,
    ): GroupReassignmentValidation

    suspend fun clearAllRingtones()

    suspend fun getContactsByIdsPreferCache(ids: List<Long>, batchSize: Int = 200): List<Contact>

    suspend fun enrichGroupContactsBasics(labelId: String, batchSize: Int = 200)

    fun getRingtoneChoiceOptions(uriList: List<String>): List<RingtoneChoiceOption>
}

internal class ContactsRepositoryImpl(
    private val app: App,
    private val helper: ContactsHelper,
    private val tracker: Telemetry,
    private val prefs: EncryptedPreferencesHelper,
    private val localLabelsStore: LocalLabelsStore,
    private val localLabelMirror: LocalContactLabelMirror,
    private val sourceProvider: () -> ContactSource?,
) : ContactsRepository {

    private val lock = Mutex()
    private val _labels = MutableStateFlow<List<LabelItem>>(emptyList())
    override val labelsFlow: StateFlow<List<LabelItem>> = _labels

    private val _contacts = MutableStateFlow<List<Contact>?>(null)
    override val allContacts: StateFlow<List<Contact>?> = _contacts

    override suspend fun loadAccountLabels() {
        lock.withLock {
            migrateLocalLabelMirrorIfNeeded()
            _labels.value = when (val source = sourceProvider()) {
                null -> {
                    tracker.trackError(RuntimeException("The contacts are queried with no source selected"))
                    helper.getAllLabelItems()
                }

                is ContactSource.CloudAccount ->
                    helper.getAllLabelItemsForAccounts(source.account)

                ContactSource.OnDevice ->
                    readLocalLabelItems(writeBackIfNeeded = true)
            }
        }
    }

    override suspend fun loadAccountLabelsShallow() {
        lock.withLock {
            migrateLocalLabelMirrorIfNeeded()
            _labels.value = when (val source = sourceProvider()) {
                null -> {
                    tracker.trackError(RuntimeException("The contacts are queried with no source selected"))
                    helper.getAllLabelItemsShallow()
                }

                is ContactSource.CloudAccount ->
                    helper.getAllLabelItemsForAccountsShallow(source.account)

                ContactSource.OnDevice ->
                    readLocalLabelItems(writeBackIfNeeded = true)
            }
        }
    }

    override suspend fun enrichRingtonesForCurrentLabels(batchSize: Int) {
        val snapshot = labelsFlow.value
        val allIds = snapshot
            .asSequence()
            .flatMap { it.contacts.asSequence() }
            .map { it.id }
            .distinct()
            .toList()
        if (allIds.isEmpty()) return

        val ringtoneMap = helper.getRingtonesForContactsBatched(allIds, batchSize)
        lock.withLock {
            _labels.update { current ->
                current.map { label ->
                    val updatedContacts = label.contacts.map { contact ->
                        val uri = ringtoneMap[contact.id]
                        if (uri == contact.ringtoneUriStr) contact else contact.copy(ringtoneUriStr = uri)
                    }
                    label.withContactsSummary(updatedContacts)
                }
            }
        }
    }

    override suspend fun setGroupRingtone(
        group: LabelItem,
        uriStr: String,
        fileName: String,
    ) {
        tracker.trackEvent("set_group_ringtone_in_repo")

        if (fileName.isNotBlank()) prefs.saveString(uriStr, fileName)

        val appliedRingtoneByContactId = helper.setRingtoneToLabelContacts(
            labelContacts = group.contacts,
            newRingtoneUriStr = uriStr
        ).asSequence()
            .mapNotNull { result ->
                result.appliedUri?.let { appliedUri -> result.contactId to appliedUri }
            }
            .toMap()

        if (appliedRingtoneByContactId.isEmpty()) {
            throw IllegalStateException("No contact ringtones were persisted for group ${group.id}")
        }

        updateGroupRingtone(group.id, appliedRingtoneByContactId)
        _contacts.update { list ->
            list?.map { contact ->
                appliedRingtoneByContactId[contact.id]?.let { appliedUri ->
                    contact.copy(ringtoneUriStr = appliedUri)
                } ?: contact
            }
        }
    }

    override suspend fun deleteGroup(group: LabelItem) {
        when (group.storageKind) {
            LabelStorageKind.PROVIDER_GROUP -> {
                val providerGroupId = group.providerGroupId
                    ?: error("Provider group id is required for provider-backed labels")
                tracker.trackEvent(
                    "delete_group",
                    mapOf("group_id" to providerGroupId.toString())
                )
                helper.deleteLabel(providerGroupId)
                _labels.update { current -> current.filter { it.id != group.id } }
            }

            LabelStorageKind.LOCAL_STORE -> {
                deleteLocalGroup(group)
            }
        }
    }

    override suspend fun createGroup(name: String) {
        when (val source = sourceProvider()) {
            is ContactSource.CloudAccount -> {
                tracker.trackEvent("create_group")
                val result = helper.createLabel(name, source.account) ?: throw Throwable()
                _labels.update { current -> current + result }
            }

            ContactSource.OnDevice -> createLocalGroup(name)

            null -> {
                tracker.trackError(RuntimeException("Group creation attempted without selected source"))
                throw IllegalStateException("No source selected")
            }
        }
    }

    override suspend fun renameGroup(group: LabelItem, newName: String) {
        when (group.storageKind) {
            LabelStorageKind.PROVIDER_GROUP -> {
                val providerGroupId = group.providerGroupId
                    ?: error("Provider group id is required for provider-backed labels")
                tracker.trackEvent("rename_group")
                helper.updateLabelName(providerGroupId, newName)
                _labels.update { current ->
                    current.map { existing ->
                        if (existing.id == group.id) existing.copy(groupName = newName) else existing
                    }
                }
            }

            LabelStorageKind.LOCAL_STORE -> renameLocalGroup(group, newName)
        }
    }

    override suspend fun updateGroupMembers(
        group: LabelItem,
        newSelected: List<Contact>,
        oldSelected: List<Contact>,
        ringtoneForNewContactsUri: String?,
    ) {
        when (group.storageKind) {
            LabelStorageKind.PROVIDER_GROUP -> updateProviderGroupMembers(
                group = group,
                newSelected = newSelected,
                oldSelected = oldSelected,
                ringtoneForNewContactsUri = ringtoneForNewContactsUri
            )

            LabelStorageKind.LOCAL_STORE -> updateLocalGroupMembers(
                group = group,
                newSelected = newSelected,
                oldSelected = oldSelected,
                ringtoneForNewContactsUri = ringtoneForNewContactsUri
            )
        }
    }

    override suspend fun validateGroupReassignment(
        group: LabelItem,
        candidates: List<Contact>,
    ): GroupReassignmentValidation {
        val distinctCandidates = candidates.distinctBy { it.id }
        if (distinctCandidates.isEmpty()) {
            return GroupReassignmentValidation(emptyList(), emptyList())
        }

        return when (group.storageKind) {
            LabelStorageKind.PROVIDER_GROUP -> {
                val providerGroupId = group.providerGroupId
                    ?: error("Provider group id is required for provider-backed labels")
                val blockedIds = helper.findBlockedContactsForLabelReassignment(
                    targetLabelId = providerGroupId,
                    contactIds = distinctCandidates.map { it.id },
                    appVisibleEditableLabelIds = labelsFlow.value.mapNotNull { it.providerGroupId }
                        .toSet()
                )
                GroupReassignmentValidation(
                    allowed = distinctCandidates.filterNot { it.id in blockedIds },
                    blocked = distinctCandidates.filter { it.id in blockedIds }
                )
            }

            LabelStorageKind.LOCAL_STORE -> {
                val blocked = distinctCandidates.filter { candidate ->
                    candidate.lookupKey.isBlank() ||
                            helper.resolveMirrorRawContactIdForPureLocalContact(candidate.id) == null
                }
                GroupReassignmentValidation(
                    allowed = distinctCandidates.filterNot { it in blocked },
                    blocked = blocked
                )
            }
        }
    }

    override suspend fun clearAllRingtones() = withContext(DispatchersProvider.io) {
        helper.clearAllRingtoneUris()
        tracker.trackEvent("clear_all_ringtones")
        _labels.update { current ->
            current.map { group ->
                group.copy(
                    ringtoneUriList = emptyList(),
                    ringtoneFileName = "",
                    contacts = group.contacts.map { it.copy(ringtoneUriStr = null) }
                )
            }
        }
    }

    override suspend fun refreshAllPhoneContacts() {
        val contacts = when (val source = sourceProvider()) {
            null -> helper.getAllPhoneContacts(null)
            is ContactSource.CloudAccount -> helper.getAllPhoneContacts(source.account)
            ContactSource.OnDevice -> helper.getPureOnDeviceContacts()
        }
        _contacts.update { contacts }
    }

    override suspend fun getContactsByIdsPreferCache(
        ids: List<Long>,
        batchSize: Int,
    ): List<Contact> {
        if (ids.isEmpty()) return emptyList()

        val cacheMap = allContacts.value?.associateBy { it.id } ?: emptyMap()
        val fromCache = ids.mapNotNull { cacheMap[it] }
        val missingIds = ids.filterNot { cacheMap.containsKey(it) }
        if (missingIds.isEmpty()) return fromCache

        val namesMap = helper.getDisplayNamesForContactsBatched(missingIds, batchSize)
        val phonesMap = helper.getPrimaryPhonesForContactsBatched(missingIds, batchSize)
        val lookupKeyMap = helper.getLookupKeysForContactIdsBatched(missingIds, batchSize)

        val fetched = missingIds.map { id ->
            Contact(
                id = id,
                lookupKey = lookupKeyMap[id].orEmpty(),
                name = namesMap[id] ?: "",
                phone = phonesMap[id],
                ringtoneUriStr = null
            )
        }

        lock.withLock {
            val merged = (_contacts.value.orEmpty() + fetched).distinctBy { it.id }
            _contacts.value = merged
        }

        val resultMap = (fromCache + fetched).associateBy { it.id }
        return ids.mapNotNull { resultMap[it] }
    }

    override suspend fun enrichGroupContactsBasics(labelId: String, batchSize: Int) {
        val label = labelsFlow.value.firstOrNull { it.id == labelId } ?: return
        if (label.storageKind == LabelStorageKind.LOCAL_STORE) {
            lock.withLock {
                _labels.value = readLocalLabelItems(writeBackIfNeeded = true)
            }
            return
        }

        val ids = label.contacts.map { it.id }.distinct()
        if (ids.isEmpty()) return
        val contacts = getContactsByIdsPreferCache(ids, batchSize)
        val byId = contacts.associateBy { it.id }

        lock.withLock {
            _labels.update { current ->
                current.map { item ->
                    if (item.id != labelId) {
                        item
                    } else {
                        val updated = item.contacts.map { contact ->
                            val enriched = byId[contact.id]
                            if (enriched == null) {
                                contact
                            } else {
                                contact.copy(
                                    lookupKey = enriched.lookupKey.ifBlank { contact.lookupKey },
                                    name = enriched.name.ifBlank { contact.name },
                                    phone = enriched.phone ?: contact.phone
                                )
                            }
                        }
                        item.copy(contacts = updated)
                    }
                }
            }
        }
    }

    override fun getRingtoneChoiceOptions(uriList: List<String>): List<RingtoneChoiceOption> {
        val uniqueUris = uriList
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (uniqueUris.isEmpty()) return emptyList()

        return uniqueUris.map { uriStr ->
            RingtoneChoiceOption(uri = uriStr, displayName = resolveRingtoneDisplayName(uriStr))
        }
    }

    private suspend fun updateProviderGroupMembers(
        group: LabelItem,
        newSelected: List<Contact>,
        oldSelected: List<Contact>,
        ringtoneForNewContactsUri: String?,
    ) {
        val providerGroupId = group.providerGroupId
            ?: error("Provider group id is required for provider-backed labels")
        val oldIds = oldSelected.mapTo(hashSetOf()) { it.id }
        val newIds = newSelected.mapTo(hashSetOf()) { it.id }
        val toAdd = newSelected.filter { it.id !in oldIds }
        val toRemove = oldSelected.filter { it.id !in newIds }

        if (toAdd.isNotEmpty()) {
            helper.reassignContactsToLabelForAppVisibleGroups(
                targetLabelId = providerGroupId,
                contactIds = toAdd.map { it.id },
                appVisibleEditableLabelIds = labelsFlow.value.mapNotNull { it.providerGroupId }
                    .toSet()
            )
        }
        if (toRemove.isNotEmpty()) {
            helper.removeAllContactsFromLabel(providerGroupId, toRemove)
        }

        val appliedRingtoneByContactId =
            applyRingtoneToAddedContacts(toAdd, ringtoneForNewContactsUri)

        tracker.trackEvent("update_group_members")
        val movedContactIds = toAdd.mapTo(hashSetOf()) { it.id }
        val selectedWithAppliedRingtone = newSelected
            .distinctBy { it.id }
            .map { contact ->
                appliedRingtoneByContactId[contact.id]?.let { appliedUri ->
                    contact.copy(ringtoneUriStr = appliedUri)
                } ?: contact
            }

        _labels.update { labels ->
            labels.map { label ->
                if (label.id == group.id) {
                    label.withContactsSummary(selectedWithAppliedRingtone)
                } else if (movedContactIds.isNotEmpty()) {
                    val filteredContacts = label.contacts.filterNot { it.id in movedContactIds }
                    if (filteredContacts.size == label.contacts.size) {
                        label
                    } else {
                        label.withContactsSummary(filteredContacts)
                    }
                } else {
                    label
                }
            }
        }

        applyRingtoneToContactsState(appliedRingtoneByContactId)
    }

    private suspend fun createLocalGroup(name: String) {
        tracker.trackEvent("create_local_group")
        val document = localLabelsStore.read()
        val label = LocalStoredLabel(
            id = UUID.randomUUID().toString(),
            name = name,
            members = emptyList()
        )
        val updatedDocument = document.copy(labels = document.labels + label)
        localLabelsStore.write(updatedDocument)
        lock.withLock {
            _labels.value = readLocalLabelItems(
                writeBackIfNeeded = true,
                recoverFromMirror = false
            )
        }
    }

    private suspend fun renameLocalGroup(group: LabelItem, newName: String) {
        tracker.trackEvent("rename_local_group")
        val localId = group.requireLocalLabelId()
        val document = localLabelsStore.read()
        val updatedDocument = document.copy(
            labels = document.labels.map { label ->
                if (label.id == localId) label.copy(name = newName) else label
            }
        )
        localLabelsStore.write(updatedDocument)
        lock.withLock {
            _labels.value = readLocalLabelItems(
                writeBackIfNeeded = true,
                recoverFromMirror = false
            )
        }
    }

    private suspend fun deleteLocalGroup(group: LabelItem) {
        tracker.trackEvent("delete_local_group")
        val localId = group.requireLocalLabelId()
        val document = localLabelsStore.read()
        val updatedDocument = document.copy(labels = document.labels.filterNot { it.id == localId })
        localLabelsStore.write(updatedDocument)
        lock.withLock {
            _labels.value = readLocalLabelItems(
                writeBackIfNeeded = true,
                recoverFromMirror = false
            )
        }
    }

    private suspend fun updateLocalGroupMembers(
        group: LabelItem,
        newSelected: List<Contact>,
        oldSelected: List<Contact>,
        ringtoneForNewContactsUri: String?,
    ) {
        tracker.trackEvent("update_local_group_members")
        val localId = group.requireLocalLabelId()
        val document = localLabelsStore.read()
        val normalizedNew = newSelected
            .filter { it.lookupKey.isNotBlank() }
            .distinctBy { it.lookupKey }
        val normalizedOld = oldSelected
            .filter { it.lookupKey.isNotBlank() }
            .distinctBy { it.lookupKey }
        val toAdd = normalizedNew.filter { candidate ->
            normalizedOld.none { it.lookupKey == candidate.lookupKey }
        }

        val movedLookupKeys = toAdd.mapTo(hashSetOf()) { it.lookupKey }
        val updatedLabels = document.labels.map { label ->
            when {
                label.id == localId -> label.copy(
                    members = normalizedNew.map { contact ->
                        LocalStoredLabelMember(
                            lookupKey = contact.lookupKey,
                            contactId = contact.id
                        )
                    }
                )

                movedLookupKeys.isNotEmpty() -> label.copy(
                    members = label.members.filterNot { member ->
                        member.lookupKey in movedLookupKeys
                    }
                )

                else -> label
            }
        }

        localLabelsStore.write(document.copy(labels = updatedLabels))

        val appliedRingtoneByContactId =
            applyRingtoneToAddedContacts(toAdd, ringtoneForNewContactsUri)
        applyRingtoneToContactsState(appliedRingtoneByContactId)

        lock.withLock {
            _labels.value = readLocalLabelItems(
                writeBackIfNeeded = true,
                recoverFromMirror = false
            )
        }
    }

    private suspend fun readLocalLabelItems(
        writeBackIfNeeded: Boolean,
        recoverFromMirror: Boolean = true,
    ): List<LabelItem> {
        val storedDocument = localLabelsStore.read()
        val mirrorReadResult = if (recoverFromMirror) {
            runCatching { localLabelMirror.readAssignments() }
                .onFailure(::trackMirrorReadFailure)
        } else {
            Result.success(emptyList())
        }
        val mirroredAssignments = mirrorReadResult.getOrDefault(emptyList())
        val recoveredDocument = if (recoverFromMirror) {
            recoverLocalLabels(storedDocument, mirroredAssignments)
        } else {
            storedDocument
        }
        if (recoverFromMirror && recoveredDocument != storedDocument) {
            trackLocalLabelRecoveryApplied(
                storedDocument = storedDocument,
                recoveredDocument = recoveredDocument,
                mirroredAssignments = mirroredAssignments
            )
        }
        val hydrated = hydrateLocalLabels(recoveredDocument)
        if (hydrated.document != recoveredDocument) {
            trackLocalLabelHydrationAdjusted(
                sourceDocument = recoveredDocument,
                hydratedDocument = hydrated.document
            )
        }
        if (writeBackIfNeeded && hydrated.document != storedDocument) {
            localLabelsStore.write(hydrated.document)
        }
        if (recoverFromMirror && writeBackIfNeeded && mirrorReadResult.isSuccess) {
            purgeLocalLabelMirror()
        }
        return hydrated.labelItems
    }

    private suspend fun migrateLocalLabelMirrorIfNeeded() {
        val storedDocument = localLabelsStore.read()
        val mirrorReadResult = runCatching { localLabelMirror.readAssignments() }
            .onFailure(::trackMirrorReadFailure)
        val mirroredAssignments = mirrorReadResult.getOrElse { return }
        if (mirroredAssignments.isEmpty()) return

        val recoveredDocument = recoverLocalLabels(storedDocument, mirroredAssignments)
        if (recoveredDocument != storedDocument) {
            trackLocalLabelRecoveryApplied(
                storedDocument = storedDocument,
                recoveredDocument = recoveredDocument,
                mirroredAssignments = mirroredAssignments
            )
        }

        val hydrated = hydrateLocalLabels(recoveredDocument)
        if (hydrated.document != recoveredDocument) {
            trackLocalLabelHydrationAdjusted(
                sourceDocument = recoveredDocument,
                hydratedDocument = hydrated.document
            )
        }
        if (hydrated.document != storedDocument) {
            localLabelsStore.write(hydrated.document)
        }
        purgeLocalLabelMirror()
    }

    private suspend fun hydrateLocalLabels(
        document: LocalLabelDocument,
    ): HydratedLocalLabels {
        val contactsByLookupKey = helper.getContactsByLookupKeys(
            document.labels.flatMap { label -> label.members.map { it.lookupKey } }
        ).associateBy { it.lookupKey }

        val cleanedLabels = document.labels.map { label ->
            label.copy(
                members = label.members
                    .filter { member -> contactsByLookupKey.containsKey(member.lookupKey) }
                    .distinctBy { it.lookupKey }
            )
        }

        val items = cleanedLabels.map { label ->
            val contacts =
                label.members.mapNotNull { member -> contactsByLookupKey[member.lookupKey] }
            LocalLabelItemFactory.toLabelItem(
                label = label,
                contacts = contacts,
                ringtoneFileNameResolver = ::deriveGroupRingtoneFileName
            )
        }
        return HydratedLocalLabels(
            document = LocalLabelDocument(version = document.version, labels = cleanedLabels),
            labelItems = items
        )
    }

    private suspend fun applyRingtoneToAddedContacts(
        contacts: List<Contact>,
        ringtoneUri: String?,
    ): Map<Long, String> {
        if (ringtoneUri == null || contacts.isEmpty()) return emptyMap()
        return helper.setRingtoneToLabelContacts(
            labelContacts = contacts,
            newRingtoneUriStr = ringtoneUri
        ).asSequence()
            .mapNotNull { result ->
                result.appliedUri?.let { appliedUri -> result.contactId to appliedUri }
            }
            .toMap()
    }

    private fun applyRingtoneToContactsState(appliedRingtoneByContactId: Map<Long, String>) {
        if (appliedRingtoneByContactId.isEmpty()) return
        _contacts.update { list ->
            list?.map { contact ->
                appliedRingtoneByContactId[contact.id]?.let { appliedUri ->
                    contact.copy(ringtoneUriStr = appliedUri)
                } ?: contact
            }
        }
    }

    private suspend fun purgeLocalLabelMirror() {
        // One-time migration path: import existing rows if needed, then remove them forever.
        runMirrorOperationSafely("purge_local_group_mirror") {
            localLabelMirror.purgeOwnedRows()
        }
    }

    private fun trackLocalLabelRecoveryApplied(
        storedDocument: LocalLabelDocument,
        recoveredDocument: LocalLabelDocument,
        mirroredAssignments: List<MirroredLocalLabelAssignment>,
    ) {
        tracker.trackEvent(
            "local_label_recovery_applied",
            mapOf(
                "stored_label_count" to storedDocument.labels.size,
                "recovered_label_count" to recoveredDocument.labels.size,
                "stored_member_count" to storedDocument.memberCount(),
                "recovered_member_count" to recoveredDocument.memberCount(),
                "mirrored_assignment_count" to mirroredAssignments.size
            )
        )
    }

    private fun trackLocalLabelHydrationAdjusted(
        sourceDocument: LocalLabelDocument,
        hydratedDocument: LocalLabelDocument,
    ) {
        tracker.trackEvent(
            "local_label_hydration_adjusted",
            mapOf(
                "source_label_count" to sourceDocument.labels.size,
                "hydrated_label_count" to hydratedDocument.labels.size,
                "source_member_count" to sourceDocument.memberCount(),
                "hydrated_member_count" to hydratedDocument.memberCount()
            )
        )
    }

    private suspend fun runMirrorOperationSafely(
        context: String,
        block: suspend () -> Unit,
    ) {
        runCatching { block() }
            .onFailure { error ->
                if (error.isContactsPermissionFailure()) return@onFailure
                tracker.trackError(error)
                tracker.trackEvent(
                    "local_label_mirror_operation_failed",
                    mapOf(
                        "context" to context,
                        "reason" to (error.message ?: error::class.java.simpleName)
                    )
                )
            }
    }

    private fun trackMirrorReadFailure(error: Throwable) {
        if (!error.isContactsPermissionFailure()) {
            tracker.trackError(error)
        }
    }

    private fun updateGroupRingtone(
        groupId: String,
        appliedRingtoneByContactId: Map<Long, String>,
    ) {
        _labels.update { current ->
            current.map { group ->
                if (group.id == groupId) {
                    val updatedContacts = group.contacts.map { contact ->
                        appliedRingtoneByContactId[contact.id]?.let { appliedUri ->
                            contact.copy(ringtoneUriStr = appliedUri)
                        } ?: contact
                    }
                    group.withContactsSummary(updatedContacts)
                } else {
                    group
                }
            }
        }
    }

    private fun deriveGroupRingtoneFileName(uris: List<String>): String {
        val distinctUris = uris.toSet()
        if (distinctUris.isEmpty()) return ""

        val names = distinctUris
            .map { uriStr -> resolveRingtoneDisplayName(uriStr) }
            .filter { it.isNotBlank() }
            .distinct()

        return if (names.isEmpty()) "" else names.joinToString(", ")
    }

    private fun resolveRingtoneDisplayName(uriStr: String): String {
        val uri = runCatching { uriStr.toUri() }.getOrNull()
        val queriedDisplayName = uri?.let(::queryDisplayNameForUri)

        return chooseRingtoneDisplayName(
            persistedDisplayName = prefs.getString(uriStr),
            queriedDisplayName = queriedDisplayName,
            unavailableMarker = app.getString(R.string.file_name_not_accessible),
            uriLastPathSegment = uri?.lastPathSegment,
            fallbackUri = uriStr
        )
    }

    private fun queryDisplayNameForUri(uri: Uri): String? {
        return runCatching {
            app.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex < 0) null else cursor.getString(nameIndex)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun LabelItem.withContactsSummary(updatedContacts: List<Contact>): LabelItem {
        val distinctUris = updatedContacts.mapNotNull { it.ringtoneUriStr }.distinct()
        return copy(
            contacts = updatedContacts,
            ringtoneUriList = distinctUris,
            ringtoneFileName = deriveGroupRingtoneFileName(distinctUris)
        )
    }

    private fun LabelItem.requireLocalLabelId(): String {
        check(storageKind == LabelStorageKind.LOCAL_STORE) {
            "Local label id requested for non-local label"
        }
        return id.removePrefix(LOCAL_LABEL_PREFIX)
    }

    private object LocalLabelItemFactory {
        fun toLabelItem(
            label: LocalStoredLabel,
            contacts: List<Contact>,
            ringtoneFileNameResolver: (List<String>) -> String,
        ): LabelItem {
            val ringtoneUris = contacts.mapNotNull { it.ringtoneUriStr }.distinct()
            return LabelItem(
                id = "$LOCAL_LABEL_PREFIX${label.id}",
                groupName = label.name,
                contacts = contacts,
                ringtoneUriList = ringtoneUris,
                ringtoneFileName = ringtoneFileNameResolver(ringtoneUris),
                canDelete = true,
                storageKind = LabelStorageKind.LOCAL_STORE,
                providerGroupId = null
            )
        }
    }

    companion object {
        private const val LOCAL_LABEL_PREFIX = "local:"
    }
}

private fun LocalLabelDocument.memberCount(): Int =
    labels.sumOf { label -> label.members.size }

private data class HydratedLocalLabels(
    val document: LocalLabelDocument,
    val labelItems: List<LabelItem>,
)

internal fun chooseRingtoneDisplayName(
    persistedDisplayName: String?,
    queriedDisplayName: String?,
    unavailableMarker: String,
    uriLastPathSegment: String?,
    fallbackUri: String,
): String {
    return persistedDisplayName
        ?.takeIf { it.isNotBlank() }
        ?: queriedDisplayName
            ?.takeUnless { it.isBlank() || it == unavailableMarker }
        ?: uriLastPathSegment
            ?.takeIf { it.isNotBlank() }
        ?: fallbackUri
}
