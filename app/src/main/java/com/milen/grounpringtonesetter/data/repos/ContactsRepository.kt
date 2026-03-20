package com.milen.grounpringtonesetter.data.repos

import androidx.core.net.toUri
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.accounts.AccountId
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import com.milen.grounpringtonesetter.utils.Tracker
import com.milen.grounpringtonesetter.utils.getFileNameOrEmpty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    suspend fun renameGroup(groupId: Long, newName: String)
    suspend fun deleteGroup(groupId: Long)

    suspend fun updateGroupMembers(
        groupId: Long,
        newSelected: List<Contact>,
        oldSelected: List<Contact>,
        ringtoneForNewContactsUri: String?,
    )

    suspend fun validateGroupReassignment(
        groupId: Long,
        candidates: List<Contact>,
    ): GroupReassignmentValidation

    suspend fun clearAllRingtones()

    suspend fun getContactsByIdsPreferCache(ids: List<Long>, batchSize: Int = 200): List<Contact>

    suspend fun enrichGroupContactsBasics(labelId: Long, batchSize: Int = 200)

    fun getRingtoneChoiceOptions(uriList: List<String>): List<RingtoneChoiceOption>
}


internal class ContactsRepositoryImpl(
    private val app: App,
    private val helper: ContactsHelper,
    private val tracker: Tracker,
    private val prefs: EncryptedPreferencesHelper,
    private val accountsProvider: () -> AccountId?,
) : ContactsRepository {

    private val lock = Mutex()
    private val _labels = MutableStateFlow<List<LabelItem>>(emptyList())
    override val labelsFlow: StateFlow<List<LabelItem>> = _labels

    private val _contacts = MutableStateFlow<List<Contact>?>(null)
    override val allContacts: StateFlow<List<Contact>?> = _contacts


    override suspend fun loadAccountLabels() {
        return lock.withLock {
            val selected = accountsProvider()
            val fresh = if (selected == null) {
                tracker.trackError(RuntimeException("The contacts are queried with no accounts selected"))
                helper.getAllLabelItems()
            } else {
                helper.getAllLabelItemsForAccounts(selected)
            }

            _labels.update { fresh }
        }
    }

    override suspend fun loadAccountLabelsShallow() {
        return lock.withLock {
            val selected = accountsProvider()
            val fresh = if (selected == null) {
                tracker.trackError(RuntimeException("The contacts are queried with no accounts selected"))
                helper.getAllLabelItemsShallow()
            } else {
                helper.getAllLabelItemsForAccountsShallow(selected)
            }
            _labels.update { fresh }
        }
    }

    override suspend fun enrichRingtonesForCurrentLabels(batchSize: Int) {
        // Take a snapshot of current labels (IDs only so far).
        val snapshot = labelsFlow.value
        val allIds = snapshot
            .asSequence()
            .flatMap { it.contacts.asSequence() }
            .map { it.id }
            .distinct()
            .toList()

        if (allIds.isEmpty()) return

        // Query ringtones off the main thread.
        val ringtoneMap = helper.getRingtonesForContactsBatched(allIds, batchSize)

        // Atomically update labels with enriched contact ringtone URIs and group summaries.
        lock.withLock {
            _labels.update { current ->
                current.map { label ->
                    val updatedContacts = label.contacts.map { c ->
                        val uri = ringtoneMap[c.id]
                        if (uri == c.ringtoneUriStr) c else c.copy(ringtoneUriStr = uri)
                    }
                    val distinctUris = updatedContacts.mapNotNull { it.ringtoneUriStr }.distinct()
                    label.copy(
                        contacts = updatedContacts,
                        ringtoneUriList = distinctUris,
                        ringtoneFileName = deriveGroupRingtoneFileName(distinctUris)
                    )
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
                result.appliedUri?.let { appliedUri ->
                    result.contactId to appliedUri
                }
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

    override suspend fun deleteGroup(groupId: Long) {
        tracker.trackEvent(
            "delete_group",
            mapOf("group_id" to groupId.toString())
        )
        helper.deleteLabel(groupId)

        val updated = _labels.value.filter { it.id != groupId }
        _labels.update { updated }
    }

    override suspend fun createGroup(name: String) {
        tracker.trackEvent("create_group")
        val result = helper.createLabel(name, accountsProvider())
        if (result == null) {
            throw Throwable()
        }

        val updated = _labels.value + result
        _labels.update { updated }
    }

    override suspend fun renameGroup(groupId: Long, newName: String) {
        tracker.trackEvent("rename_group")
        helper.updateLabelName(groupId, newName)
        val updated = _labels.value.map { g ->
            if (g.id == groupId) g.copy(groupName = newName) else g
        }
        _labels.update { updated }
    }

    override suspend fun updateGroupMembers(
        groupId: Long,
        newSelected: List<Contact>,
        oldSelected: List<Contact>,
        ringtoneForNewContactsUri: String?,
    ) {
        val oldIds =
            HashSet<Long>(oldSelected.size).apply { oldSelected.forEach { add(it.id) } }
        val newIds =
            HashSet<Long>(newSelected.size).apply { newSelected.forEach { add(it.id) } }

        val toAdd =
            if (newSelected.isEmpty()) emptyList() else newSelected.filter { it.id !in oldIds }
        val toRemove =
            if (oldSelected.isEmpty()) emptyList() else oldSelected.filter { it.id !in newIds }

        if (toAdd.isNotEmpty()) {
            helper.reassignContactsToLabelForAppVisibleGroups(
                targetLabelId = groupId,
                contactIds = toAdd.map { it.id },
                appVisibleEditableLabelIds = _labels.value.map { it.id }.toSet()
            )
        }
        if (toRemove.isNotEmpty()) helper.removeAllContactsFromLabel(groupId, toRemove)
        val appliedRingtoneByContactId = if (ringtoneForNewContactsUri != null && toAdd.isNotEmpty()) {
            helper.setRingtoneToLabelContacts(
                labelContacts = toAdd,
                newRingtoneUriStr = ringtoneForNewContactsUri
            ).asSequence()
                .mapNotNull { result ->
                    result.appliedUri?.let { appliedUri ->
                        result.contactId to appliedUri
                    }
                }
                .toMap()
        } else {
            emptyMap()
        }

        tracker.trackEvent("update_group_members")
        val movedContactIds = toAdd.map { it.id }.toHashSet()
        val selectedWithRingtoneApplied = newSelected
            .distinctBy { it.id }
            .map { contact ->
                appliedRingtoneByContactId[contact.id]?.let { appliedUri ->
                    contact.copy(ringtoneUriStr = appliedUri)
                } ?: contact
            }

        _labels.update { labels ->
            labels.map { label ->
                if (label.id == groupId) {
                    label.withContactsSummary(selectedWithRingtoneApplied)
                } else if (movedContactIds.isNotEmpty()) {
                    val filteredContacts = label.contacts.filterNot { it.id in movedContactIds }
                    if (filteredContacts.size == label.contacts.size) {
                        label
                    } else {
                        label.withContactsSummary(filteredContacts)
                    }
                } else label
            }
        }

        if (appliedRingtoneByContactId.isNotEmpty()) {
            _contacts.update { list ->
                list?.map { contact ->
                    appliedRingtoneByContactId[contact.id]?.let { appliedUri ->
                        contact.copy(ringtoneUriStr = appliedUri)
                    } ?: contact
                }
            }
        }
    }

    override suspend fun validateGroupReassignment(
        groupId: Long,
        candidates: List<Contact>,
    ): GroupReassignmentValidation {
        val distinctCandidates = candidates.distinctBy { it.id }
        if (distinctCandidates.isEmpty()) {
            return GroupReassignmentValidation(
                allowed = emptyList(),
                blocked = emptyList()
            )
        }

        val blockedIds = helper.findBlockedContactsForLabelReassignment(
            targetLabelId = groupId,
            contactIds = distinctCandidates.map { it.id },
            appVisibleEditableLabelIds = labelsFlow.value.map { it.id }.toSet()
        )

        val blocked = distinctCandidates.filter { it.id in blockedIds }
        val allowed = distinctCandidates.filterNot { it.id in blockedIds }
        return GroupReassignmentValidation(
            allowed = allowed,
            blocked = blocked
        )
    }

    override suspend fun clearAllRingtones() = withContext(DispatchersProvider.io) {
        helper.clearAllRingtoneUris()
        tracker.trackEvent("clear_all_ringtones")
        val updated = _labels.value.map { g ->
            g.copy(
                ringtoneUriList = emptyList(),
                ringtoneFileName = "",
                contacts = g.contacts.map { it.copy(ringtoneUriStr = null) }
            )
        }
        _labels.update { updated }
    }


    override suspend fun refreshAllPhoneContacts() {
        val accountId = accountsProvider()
        tracker.trackEvent(
            "get_all_phone_contacts",
            mapOf(
                "has_account" to (accountId != null),
                "account_sig" to accountSignature(accountId)
            )
        )
        val contacts = helper.getAllPhoneContacts(accountId)
        _contacts.update { contacts }
    }
    override suspend fun getContactsByIdsPreferCache(
        ids: List<Long>,
        batchSize: Int,
    ): List<Contact> {
        if (ids.isEmpty()) return emptyList()

        // 1) try cache first
        val cacheMap = allContacts.value?.associateBy { it.id } ?: emptyMap()
        val fromCache = ids.mapNotNull { cacheMap[it] }
        val missingIds = ids.filterNot { cacheMap.containsKey(it) }
        if (missingIds.isEmpty()) return fromCache

        // 2) fetch only missing (batched, off-main inside helpers)
        val namesMap = helper.getDisplayNamesForContactsBatched(missingIds, batchSize)
        val phonesMap = helper.getPrimaryPhonesForContactsBatched(missingIds, batchSize)

        val fetched = missingIds.map { id ->
            Contact(
                id = id,
                name = namesMap[id] ?: "",
                phone = phonesMap[id],
                ringtoneUriStr = null // ringtone remains group-specific; don't touch here
            )
        }

        // 3) merge into cache
        lock.withLock {
            val cur = _contacts.value.orEmpty()
            val merged = (cur + fetched).distinctBy { it.id }
            _contacts.value = merged
        }

        // 4) return in requested order
        val resultMap = (fromCache + fetched).associateBy { it.id }
        return ids.mapNotNull { resultMap[it] }
    }

    override suspend fun enrichGroupContactsBasics(labelId: Long, batchSize: Int) {
        // Snapshot target label & contact IDs
        val label = labelsFlow.value.firstOrNull { it.id == labelId } ?: return
        val ids = label.contacts.map { it.id }.distinct()
        if (ids.isEmpty()) return

        // Resolve via cache; fetch only what's missing
        val contacts = getContactsByIdsPreferCache(ids, batchSize)
        val byId = contacts.associateBy { it.id }

        // Update ONLY this label's contacts with name/phone (keep ringtone as-is)
        lock.withLock {
            _labels.update { current ->
                current.map { item ->
                    if (item.id != labelId) item else {
                        val updated = item.contacts.map { c ->
                            val enriched = byId[c.id]
                            if (enriched == null) c else c.copy(
                                name = enriched.name.ifBlank { c.name },
                                phone = enriched.phone ?: c.phone
                            )
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
            val displayName = prefs.getString(uriStr)
                ?.takeIf { it.isNotBlank() }
                ?: runCatching { uriStr.toUri().lastPathSegment }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                ?: uriStr

            RingtoneChoiceOption(
                uri = uriStr,
                displayName = displayName
            )
        }
    }

    private fun updateGroupRingtone(
        groupId: Long,
        appliedRingtoneByContactId: Map<Long, String>,
    ) {
        val updated = _labels.value.map { g ->
            if (g.id == groupId) {
                val updatedContacts = g.contacts.map { contact ->
                    appliedRingtoneByContactId[contact.id]?.let { appliedUri ->
                        contact.copy(ringtoneUriStr = appliedUri)
                    } ?: contact
                }
                g.withContactsSummary(updatedContacts)
            } else g
        }

        _labels.update { updated }
    }

    private fun deriveGroupRingtoneFileName(uris: List<String>): String {
        val distinctUris = uris.toSet()
        if (distinctUris.isEmpty()) return ""

        // 2) Map each distinct URI to a human-friendly filename
        val names = distinctUris.mapNotNull { uriStr ->
            val uri = runCatching { uriStr.toUri() }.getOrNull() ?: return@mapNotNull null
            val fromExt = runCatching { uri.getFileNameOrEmpty(app) }.getOrNull()
            when {
                !fromExt.isNullOrBlank() -> fromExt
                !uri.lastPathSegment.isNullOrBlank() -> uri.lastPathSegment
                else -> null
            }
        }.distinct()

        // 3) Join for display (or empty if nothing resolved)
        return if (names.isEmpty()) "" else names.joinToString(", ")
    }

    private fun LabelItem.withContactsSummary(updatedContacts: List<Contact>): LabelItem {
        val distinctUris = updatedContacts.mapNotNull { it.ringtoneUriStr }.distinct()
        return copy(
            contacts = updatedContacts,
            ringtoneUriList = distinctUris,
            ringtoneFileName = deriveGroupRingtoneFileName(distinctUris)
        )
    }

    private fun accountSignature(accountId: AccountId?): String {
        val raw = accountId?.raw.orEmpty()
        if (raw.isBlank()) return "none"
        return raw.hashCode().toUInt().toString(16)
    }
}
