package com.milen.grounpringtonesetter.utils

import android.Manifest
import android.accounts.AccountManager
import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.LabelStorageKind
import com.milen.grounpringtonesetter.data.accounts.AccountId
import com.milen.grounpringtonesetter.data.exceptions.DeleteLabelException
import com.milen.grounpringtonesetter.data.exceptions.DeleteLabelFailureReason
import com.milen.grounpringtonesetter.data.exceptions.NoContactsFoundException
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import kotlinx.coroutines.withContext

internal class ContactsHelper(
    private val appContext: Application,
    private val preferenceHelper: EncryptedPreferencesHelper,
    private val contactRingtoneUpdateHelper: ContactRingtoneUpdateHelper,
    private val tracker: Telemetry,
) {
    private companion object {
        private const val MAX_CONTENT_PROVIDER_OPERATIONS_PER_BATCH = 400

        private val GROUPS_LIST_PROJECTION = arrayOf(
            ContactsContract.Groups._ID,
            ContactsContract.Groups.TITLE,
            ContactsContract.Groups.ACCOUNT_TYPE,
            ContactsContract.Groups.ACCOUNT_NAME,
            ContactsContract.Groups.GROUP_IS_READ_ONLY,
            ContactsContract.Groups.DELETED,
            ContactsContract.Groups.SYSTEM_ID
        )

        private val GROUP_DELETE_CHECK_PROJECTION = arrayOf(
            ContactsContract.Groups._ID,
            ContactsContract.Groups.GROUP_IS_READ_ONLY,
            ContactsContract.Groups.DELETED,
            ContactsContract.Groups.SYSTEM_ID
        )

        private val CONTACTS_LIST_PROJECTION = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.CUSTOM_RINGTONE
        )

        private val RAW_CONTACTS_PROJECTION = arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.CONTACT_ID,
            ContactsContract.RawContacts.ACCOUNT_NAME,
            ContactsContract.RawContacts.ACCOUNT_TYPE
        )
    }

    private data class GroupMetadata(
        val id: Long,
        val isReadOnly: Boolean,
        val isDeleted: Boolean,
        val systemId: String?,
    )

    private data class BatchApplyOutcome(
        val chunkCount: Int,
        val appliedResultCount: Int,
    )

    private data class RawContactDescriptor(
        val rawContactId: Long,
        val contactId: Long,
        val accountName: String?,
        val accountType: String?,
    )

    private data class ContactRow(
        val id: Long,
        val lookupKey: String,
        val name: String,
        val ringtoneUriStr: String?,
    )

    private fun GroupMetadata.toDeletionCapability(): GroupDeletionCapability = GroupDeletionCapability(
        isDeleted = isDeleted,
        isReadOnly = isReadOnly,
        systemId = systemId
    )

    private fun resolveGroupCanDelete(
        isReadOnly: Boolean,
        isDeleted: Boolean,
        systemId: String?,
    ): Boolean = canDeleteGroup(
        GroupDeletionCapability(
            isDeleted = isDeleted,
            isReadOnly = isReadOnly,
            systemId = systemId
        )
    )

    private fun ContactRow.toContact(
        phoneByContactId: Map<Long, String?>,
        ringtoneByContactId: Map<Long, String?> = emptyMap(),
    ): Contact {
        val resolvedRingtone = if (ringtoneByContactId.containsKey(id)) {
            ringtoneByContactId[id]
        } else {
            ringtoneUriStr
        }
        return Contact(
            id = id,
            lookupKey = lookupKey,
            name = name,
            phone = phoneByContactId[id],
            ringtoneUriStr = resolvedRingtone
        )
    }

    private fun queryGroupMetadata(labelId: Long): GroupMetadata? {
        val selection = "${ContactsContract.Groups._ID} = ?"
        val args = arrayOf(labelId.toString())
        appContext.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            GROUP_DELETE_CHECK_PROJECTION,
            selection,
            args,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups._ID)
            val isReadOnlyIndex =
                cursor.getColumnIndexOrThrow(ContactsContract.Groups.GROUP_IS_READ_ONLY)
            val isDeletedIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.DELETED)
            val systemIdIndex = cursor.getColumnIndex(ContactsContract.Groups.SYSTEM_ID)
            val systemId = if (systemIdIndex >= 0 && !cursor.isNull(systemIdIndex)) {
                cursor.getString(systemIdIndex)
            } else {
                null
            }
            return GroupMetadata(
                id = cursor.getLong(idIndex),
                isReadOnly = cursor.getInt(isReadOnlyIndex) != 0,
                isDeleted = cursor.getInt(isDeletedIndex) != 0,
                systemId = systemId
            )
        }
        return null
    }

    fun hasReadContactsPermission(): Boolean =
        hasContactsPermission(Manifest.permission.READ_CONTACTS)

    fun hasWriteContactsPermission(): Boolean =
        hasContactsPermission(Manifest.permission.WRITE_CONTACTS)

    private fun hasContactsPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            permission
        ) == PackageManager.PERMISSION_GRANTED

    private fun getContactIdsForLabel(labelId: Long): List<Long> {
        val ids = LinkedHashSet<Long>() // dedupe if multiple raw contacts map to same CONTACT_ID
        val uri = ContactsContract.Data.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTACT_ID)
        val selection =
            "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID}=? AND " +
                    "${ContactsContract.Data.MIMETYPE}=?"
        val args = arrayOf(
            labelId.toString(),
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
        )
        appContext.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
            val idxId =
                c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.GroupMembership.CONTACT_ID)
            while (c.moveToNext()) ids.add(c.getLong(idxId))
        }
        return ids.toList()
    }

    suspend fun getAllLabelItemsShallow(
        includeDeviceContacts: Boolean = true,
    ): List<LabelItem> = withContext(DispatchersProvider.io) {
        if (!hasReadContactsPermission()) {
            tracker.trackEvent("contacts_permission_missing_labels_shallow")
            return@withContext emptyList()
        }
        tracker.trackEvent("getAllLabels SHALLOW called")
        val out = ArrayList<LabelItem>()
        val uri = ContactsContract.Groups.CONTENT_URI
        val projection = GROUPS_LIST_PROJECTION
        val selection = if (includeDeviceContacts) {
            "${ContactsContract.Groups.DELETED}=0 AND ${ContactsContract.Groups.GROUP_IS_READ_ONLY}=0"
        } else {
            "${ContactsContract.Groups.DELETED}=0 AND ${ContactsContract.Groups.GROUP_IS_READ_ONLY}=0 " +
                    "AND ${ContactsContract.Groups.ACCOUNT_TYPE}=?"
        }
        val selectionArgs = if (includeDeviceContacts) null else arrayOf("com.google")

        try {
            appContext.contentResolver.query(uri, projection, selection, selectionArgs, null)
                ?.use { c ->
                    val idxId = c.getColumnIndexOrThrow(ContactsContract.Groups._ID)
                    val idxTitle = c.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)
                    val idxReadOnly =
                        c.getColumnIndexOrThrow(ContactsContract.Groups.GROUP_IS_READ_ONLY)
                    val idxDeleted = c.getColumnIndexOrThrow(ContactsContract.Groups.DELETED)
                    val idxSystemId = c.getColumnIndexOrThrow(ContactsContract.Groups.SYSTEM_ID)
                    while (c.moveToNext()) {
                        val gid = c.getLong(idxId)
                        val gname = c.getString(idxTitle) ?: ""
                        val canDelete = resolveGroupCanDelete(
                            isReadOnly = c.getInt(idxReadOnly) != 0,
                            isDeleted = c.getInt(idxDeleted) != 0,
                            systemId = c.getString(idxSystemId)
                        )
                        val ids = getContactIdsForLabel(gid)
                        val contacts = ids.map { id ->
                            Contact(
                                id = id,
                                lookupKey = "",
                                name = "",
                                phone = null,
                                ringtoneUriStr = null
                            )
                        }
                        out.add(
                            LabelItem(
                                id = providerGroupKey(gid),
                                groupName = gname,
                                contacts = contacts,
                                ringtoneUriList = emptyList(),
                                ringtoneFileName = "",
                                canDelete = canDelete,
                                storageKind = LabelStorageKind.PROVIDER_GROUP,
                                providerGroupId = gid
                            )
                        )
                    }
                }
        } catch (_: SecurityException) {
            tracker.trackEvent("contacts_permission_missing_labels_shallow")
            return@withContext emptyList()
        }
        out
    }

    suspend fun getAllLabelItemsForAccountsShallow(
        selectedAccounts: AccountId,
    ): List<LabelItem> = withContext(DispatchersProvider.io) {
        if (!hasReadContactsPermission()) {
            tracker.trackEvent("contacts_permission_missing_labels_for_account")
            return@withContext emptyList()
        }
        val allowed = mutableSetOf<Long>()
        val where =
            "${ContactsContract.Groups.DELETED}=0 AND " +
                    "${ContactsContract.Groups.ACCOUNT_TYPE}=? AND " +
                    "${ContactsContract.Groups.ACCOUNT_NAME}=?"
        val args = arrayOf(selectedAccounts.type, selectedAccounts.name)
        try {
            appContext.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups._ID),
                where,
                args,
                null
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(ContactsContract.Groups._ID)
                while (c.moveToNext()) allowed.add(c.getLong(idx))
            }
        } catch (_: SecurityException) {
            tracker.trackEvent("contacts_permission_missing_labels_for_account")
            return@withContext emptyList()
        }
        if (allowed.isEmpty()) return@withContext emptyList()

        val allShallow = getAllLabelItemsShallow(includeDeviceContacts = true)
        allShallow.filter { it.providerGroupId in allowed }
    }

    suspend fun getRingtonesForContactsBatched(
        contactIds: List<Long>,
        batchSize: Int = 100,
    ): Map<Long, String?> = withContext(DispatchersProvider.io) {
        if (contactIds.isEmpty()) return@withContext emptyMap<Long, String?>()
        val resolver = appContext.contentResolver
        val result = HashMap<Long, String?>()

        contactIds.chunked(batchSize).forEach { chunk ->
            if (chunk.isEmpty()) return@forEach
            val selection = "${ContactsContract.Contacts._ID} IN (${chunk.joinToString(",")})"
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.CUSTOM_RINGTONE
            )
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                selection,
                null,
                null
            )?.use { c ->
                val idxId = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val idxTone = c.getColumnIndexOrThrow(ContactsContract.Contacts.CUSTOM_RINGTONE)
                while (c.moveToNext()) {
                    val id = c.getLong(idxId)
                    val tone = if (!c.isNull(idxTone)) c.getString(idxTone) else null
                    result[id] = tone
                }
            }
        }
        result
    }

    suspend fun getAllPhoneContacts(accountId: AccountId?): List<Contact> =
        withContext(DispatchersProvider.io) {
            tracker.trackEvent(
                "getAllPhoneContacts called",
                mapOf("account" to (accountId?.label ?: "ALL"))
            )

            val cr = appContext.contentResolver

            suspend fun queryContacts(selection: String?, args: Array<String>?): List<Contact> {
                val rows = mutableListOf<ContactRow>()
                cr.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    CONTACTS_LIST_PROJECTION,
                    selection,
                    args,
                    null
                )
                    ?.use { cursor ->
                        val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                        val lookupKeyIdx =
                            cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                        val nameIdx =
                            cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                        val toneIdx =
                            cursor.getColumnIndexOrThrow(ContactsContract.Contacts.CUSTOM_RINGTONE)

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idIdx)
                            val lookupKey = cursor.getString(lookupKeyIdx).orEmpty()
                            val name = cursor.getString(nameIdx).orEmpty()
                            val ringtoneUriStr = cursor.getString(toneIdx)
                            rows.add(
                                ContactRow(
                                    id = id,
                                    lookupKey = lookupKey,
                                    name = name,
                                    ringtoneUriStr = ringtoneUriStr
                                )
                            )
                        }
                    } ?: throw NoContactsFoundException()
                val phoneByContactId = getPrimaryPhonesForContactsBatched(rows.map { it.id })
                return rows.map { row -> row.toContact(phoneByContactId) }
            }

            // If no account was provided: track non-fatal and return ALL contacts (your original behavior)
            if (accountId == null) {
                tracker.trackEvent(
                    "getAllPhoneContacts accountId null",
                    mapOf("non_fatal" to "true")
                )
                return@withContext queryContacts(null, null).also {
                    tracker.trackEvent(
                        "getAllPhoneContacts loaded",
                        mapOf("count" to it.size.toString(), "account" to "ALL")
                    )
                }
            }

            // 1) Find contact IDs that belong to the given account (RawContacts -> CONTACT_ID)
            val rawSel = "${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
                    "${ContactsContract.RawContacts.DELETED}=0"
            val rawArgs = arrayOf(accountId.name, accountId.type)

            val contactIds = LinkedHashSet<Long>() // stable order
            cr.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                rawSel,
                rawArgs,
                null
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)
                while (c.moveToNext()) contactIds.add(c.getLong(idx))
            }

            if (contactIds.isEmpty()) {
                tracker.trackEvent(
                    "getAllPhoneContacts loaded",
                    mapOf("count" to "0", "account" to accountId.label)
                )
                return@withContext emptyList()
            }

            // 2) Query Contacts for those IDs (batched to avoid SQLite arg limits ~999)
            val results = mutableListOf<Contact>()
            val idsList = contactIds.toList()
            val batchSize = 900
            var start = 0
            while (start < idsList.size) {
                val end = minOf(start + batchSize, idsList.size)
                val batch = idsList.subList(start, end)
                val placeholders = batch.joinToString(",") { "?" }
                val sel = "${ContactsContract.Contacts._ID} IN ($placeholders)"
                val args = batch.map { it.toString() }.toTypedArray()
                results += queryContacts(sel, args)
                start = end
            }

            return@withContext results.also {
                tracker.trackEvent(
                    "getAllPhoneContacts loaded",
                    mapOf("count" to it.size.toString(), "account" to accountId.label)
                )
            }
        }

    fun hasPureOnDeviceContacts(): Boolean =
        runCatching { pureLocalContactIds().isNotEmpty() }.getOrDefault(false)

    suspend fun getPureOnDeviceContacts(): List<Contact> = withContext(DispatchersProvider.io) {
        val ids = pureLocalContactIds().toList()
        if (ids.isEmpty()) return@withContext emptyList()
        queryContactsByIds(ids)
    }

    fun resolveMirrorRawContactIdForPureLocalContact(contactId: Long): Long? {
        val rawContacts = getRawContactDescriptors().filter { it.contactId == contactId }
        if (rawContacts.isEmpty()) return null
        val detectionContext = buildLocalContactDetectionContext()
        return if (isOnDeviceEligibleContact(rawContacts, detectionContext)) {
            rawContacts
                .filter { descriptor -> isLocalRawContact(descriptor, detectionContext) }
                .minByOrNull { it.rawContactId }
                ?.rawContactId
        } else {
            null
        }
    }

    fun updateLabelName(labelId: Long, newLabelName: String) {
        tracker.trackEvent(
            "updateLabelName called",
            mapOf("labelId" to labelId.toString(), "newLabelName" to newLabelName)
        )

        val labelNameValues = ContentValues().apply {
            put(ContactsContract.Groups.TITLE, newLabelName)
        }

        val selection = "${ContactsContract.Groups._ID} = ?"
        val selectionArgs = arrayOf(labelId.toString())

        val rowsUpdated = appContext.contentResolver.update(
            ContactsContract.Groups.CONTENT_URI,
            labelNameValues,
            selection,
            selectionArgs
        )

        if (rowsUpdated <= 0) {
            val errorMessage = "Failed to update label name for labelId: $labelId"
            tracker.trackError(IllegalStateException(errorMessage))
            throw IllegalStateException(errorMessage)
        }

        triggerSyncForAllAccounts()
        tracker.trackEvent("updateLabelName successful", mapOf("labelId" to labelId.toString()))
    }

    fun deleteLabel(labelId: Long) {
        tracker.trackEvent("deleteLabel called", mapOf("labelId" to labelId.toString()))

        val metadataBeforeDelete = queryGroupMetadata(labelId)
        if (metadataBeforeDelete == null || metadataBeforeDelete.isDeleted) {
            tracker.trackEvent(
                "deleteLabel no-op because group missing/deleted",
                mapOf("labelId" to labelId.toString())
            )
            return
        }

        if (!canDeleteGroup(metadataBeforeDelete.toDeletionCapability())) {
            val failure = DeleteLabelException(
                labelId = metadataBeforeDelete.id,
                reason = DeleteLabelFailureReason.GROUP_PROTECTED
            )
            tracker.trackError(failure)
            throw failure
        }

        // Remove label associations from all contacts
        removeAllContactsFromLabel(labelId)

        // Delete the label itself
        val labelUri = ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, labelId)

        val rowsDeleted = appContext.contentResolver.delete(labelUri, null, null)

        if (rowsDeleted <= 0) {
            val metadataAfterDelete = queryGroupMetadata(labelId)
            if (metadataAfterDelete == null || metadataAfterDelete.isDeleted) {
                triggerSyncForAllAccounts()
                tracker.trackEvent(
                    "deleteLabel successful after metadata recheck",
                    mapOf("labelId" to labelId.toString())
                )
                return
            }
            val failure = DeleteLabelException(
                labelId = metadataAfterDelete.id,
                reason = DeleteLabelFailureReason.DELETE_FAILED
            )
            tracker.trackError(failure)
            throw failure
        }

        triggerSyncForAllAccounts()

        tracker.trackEvent("deleteLabel successful", mapOf("labelId" to labelId.toString()))
    }

    fun findBlockedContactsForLabelReassignment(
        targetLabelId: Long,
        contactIds: List<Long>,
        appVisibleEditableLabelIds: Set<Long>,
    ): Set<Long> {
        val distinctContactIds = contactIds.distinct()
        if (distinctContactIds.isEmpty()) return emptySet()

        val blocked = LinkedHashSet<Long>()
        distinctContactIds.forEach { contactId ->
            if (isContactAlreadyAssignedToLabel(targetLabelId, contactId)) return@forEach
            val rawContactId =
                resolveRawContactIdForTargetInsert(contactId, appVisibleEditableLabelIds)
            if (rawContactId == null) {
                blocked.add(contactId)
            }
        }
        return blocked
    }

    fun reassignContactsToLabelForAppVisibleGroups(
        targetLabelId: Long,
        contactIds: List<Long>,
        appVisibleEditableLabelIds: Set<Long>,
    ) {
        val distinctContactIds = contactIds.distinct()
        if (distinctContactIds.isEmpty()) return

        tracker.trackEvent(
            "reassignContactsToLabelForAppVisibleGroups called",
            mapOf(
                "targetLabelId" to targetLabelId.toString(),
                "contactCount" to distinctContactIds.size.toString(),
                "groupCount" to appVisibleEditableLabelIds.size.toString()
            )
        )

        val blockedContactIds = findBlockedContactsForLabelReassignment(
            targetLabelId = targetLabelId,
            contactIds = distinctContactIds,
            appVisibleEditableLabelIds = appVisibleEditableLabelIds
        )
        if (blockedContactIds.isNotEmpty()) {
            val errorMessage = "Failed to reassign contacts without raw-contact mapping: " +
                    blockedContactIds.joinToString(",")
            throw IllegalStateException(errorMessage)
        }

        val removableGroupIds = appVisibleEditableLabelIds
            .asSequence()
            .filter { it != targetLabelId }
            .distinct()
            .toList()

        val ops = ArrayList<ContentProviderOperation>()
        distinctContactIds.forEach { contactId ->
            if (!isContactAlreadyAssignedToLabel(targetLabelId, contactId)) {
                val rawContactId = resolveRawContactIdForTargetInsert(
                    contactId = contactId,
                    appVisibleEditableLabelIds = appVisibleEditableLabelIds
                ) ?: throw IllegalStateException(
                    "Failed to resolve raw-contact mapping for contactId: $contactId"
                )

                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
                        )
                        .withValue(
                            ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID,
                            targetLabelId
                        )
                        .build()
                )
            }

            if (removableGroupIds.isNotEmpty()) {
                val groupPlaceholders = removableGroupIds.joinToString(",") { "?" }
                val selection =
                    "${ContactsContract.CommonDataKinds.GroupMembership.CONTACT_ID} = ? AND " +
                            "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} IN ($groupPlaceholders) AND " +
                            "${ContactsContract.Data.MIMETYPE} = ?"
                val selectionArgs = (listOf(contactId.toString()) +
                        removableGroupIds.map { it.toString() } +
                        ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                    .toTypedArray()
                ops.add(
                    ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection(selection, selectionArgs)
                        .build()
                )
            }
        }

        val batchOutcome = applyOperationsInChunks(
            operations = ops,
            operationContext = "reassignContactsToLabelForAppVisibleGroups",
            metadata = mapOf(
                "targetLabelId" to targetLabelId.toString(),
                "contactCount" to distinctContactIds.size.toString()
            )
        )

        triggerSyncForAllAccounts()

        tracker.trackEvent(
            "reassignContactsToLabelForAppVisibleGroups successful",
            mapOf(
                "targetLabelId" to targetLabelId.toString(),
                "contactCount" to distinctContactIds.size.toString(),
                "operationsCount" to ops.size.toString(),
                "batchResultsCount" to batchOutcome.appliedResultCount.toString(),
                "batchChunkCount" to batchOutcome.chunkCount.toString()
            )
        )
    }

    fun removeAllContactsFromLabel(labelId: Long, excludedContacts: List<Contact>) {
        tracker.trackEvent(
            "removeAllContactsFromLabel called",
            mapOf(
                "labelId" to labelId.toString(),
                "contactCount" to excludedContacts.size.toString()
            )
        )
        val ops = ArrayList<ContentProviderOperation>()

        excludedContacts.forEach { contact ->
            val selection =
                "${ContactsContract.CommonDataKinds.GroupMembership.CONTACT_ID} = ? AND " +
                        "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ? AND " +
                        "${ContactsContract.CommonDataKinds.GroupMembership.MIMETYPE} = ?"
            val selectionArgs = arrayOf(
                contact.id.toString(),
                labelId.toString(),
                ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
            )

            ops.add(
                ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection(selection, selectionArgs)
                    .build()
            )
        }

        val batchOutcome = applyOperationsInChunks(
            operations = ops,
            operationContext = "removeAllContactsFromLabel",
            metadata = mapOf(
                "labelId" to labelId.toString(),
                "contactCount" to excludedContacts.size.toString()
            )
        )

        triggerSyncForAllAccounts()

        tracker.trackEvent(
            "removeAllContactsFromLabel successful",
            mapOf(
                "labelId" to labelId.toString(),
                "removedCount" to batchOutcome.appliedResultCount.toString(),
                "operationsCount" to ops.size.toString(),
                "batchChunkCount" to batchOutcome.chunkCount.toString()
            )
        )
    }

    suspend fun getAllLabelItems(includeDeviceContacts: Boolean = true): List<LabelItem> =
        withContext(DispatchersProvider.io) {
            tracker.trackEvent("getAllLabels called")
            val labels = mutableListOf<LabelItem>()
            val uri = ContactsContract.Groups.CONTENT_URI
            val projection = GROUPS_LIST_PROJECTION

            // Build selection query based on user preference
            val selection = if (includeDeviceContacts) {
                "${ContactsContract.Groups.DELETED} = 0 AND ${ContactsContract.Groups.GROUP_IS_READ_ONLY} = 0"
            } else {
                "${ContactsContract.Groups.DELETED} = 0 AND ${ContactsContract.Groups.GROUP_IS_READ_ONLY} = 0 AND ${ContactsContract.Groups.ACCOUNT_TYPE} = ?"
            }

            val selectionArgs = if (includeDeviceContacts) null else arrayOf("com.google")

            // Query the ContentResolver
            appContext.contentResolver.query(uri, projection, selection, selectionArgs, null)
                ?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups._ID)
                    val titleIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)
                    val readOnlyIndex =
                        cursor.getColumnIndexOrThrow(ContactsContract.Groups.GROUP_IS_READ_ONLY)
                    val deletedIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.DELETED)
                    val systemIdIndex =
                        cursor.getColumnIndexOrThrow(ContactsContract.Groups.SYSTEM_ID)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIndex)
                        val title = cursor.getString(titleIndex).orEmpty()
                        if (title.isBlank()) continue
                        labels.add(
                            buildProviderLabelItem(
                                id = id,
                                title = title,
                                isReadOnly = cursor.getInt(readOnlyIndex) != 0,
                                isDeleted = cursor.getInt(deletedIndex) != 0,
                                systemId = cursor.getString(systemIdIndex)
                            )
                        )
                    }
                } ?: tracker.trackEvent("Query returned null cursor for Labels")

            return@withContext labels
        }

    suspend fun setRingtoneToLabelContacts(
        labelContacts: List<Contact>,
        newRingtoneUriStr: String,
    ): List<ContactRingtoneWriteResult> {
        return withContext(DispatchersProvider.io) {
            tracker.trackEvent(
                "set_ringtone_to_label_contacts_start",
                mapOf(
                    "label_id" to "label_contacts",
                    "contact_count" to labelContacts.size.toString(),
                    "ringtone_uri_sig" to ringtoneUriSignature(newRingtoneUriStr)
                )
            )

            if (labelContacts.isEmpty()) {
                return@withContext emptyList()
            }

            val preparedRingtone = contactRingtoneUpdateHelper.preparePlayableRingtone(
                context = appContext,
                ringtoneStr = newRingtoneUriStr
            )
            val results = if (preparedRingtone == null) {
                labelContacts.map { contact ->
                    ContactRingtoneWriteResult(contactId = contact.id, appliedUri = null)
                }
            } else {
                labelContacts.map { contact ->
                    contactRingtoneUpdateHelper.scanAndUpdatePrepared(
                        context = appContext,
                        preparedRingtone = preparedRingtone,
                        contactId = contact.id
                    )
                }
            }
            val appliedCount = results.count { it.isSuccessful }
            if (appliedCount > 0) {
                triggerSyncForAllAccounts()
            }

            tracker.trackEvent(
                "set_ringtone_to_label_contacts_result",
                mapOf(
                    "contactCount" to labelContacts.size.toString(),
                    "appliedCount" to appliedCount.toString()
                )
            )
            return@withContext results
        }
    }

    fun clearAllRingtoneUris() {
        tracker.trackEvent("clear_all_ringtone_uris_start")

        // 1. Clear ringtones for ALL contacts (without filtering by account type)
        val contactIds = getAllContactIds()
        tracker.trackEvent(
            "clear_all_ringtone_uris_contacts_loaded",
            mapOf("count" to contactIds.size.toString())
        )

        if (contactIds.isNotEmpty()) {
            val contactSelection =
                "${ContactsContract.Contacts._ID} IN (${contactIds.joinToString()})"
            val contactValues =
                ContentValues().apply { putNull(ContactsContract.Contacts.CUSTOM_RINGTONE) }

            try {
                val contactRowsUpdated = appContext.contentResolver.update(
                    ContactsContract.Contacts.CONTENT_URI,
                    contactValues,
                    contactSelection,
                    null
                )
                tracker.trackEvent(
                    "clear_all_ringtone_uris_contacts_updated",
                    mapOf("clearedCount" to contactRowsUpdated.toString())
                )
            } catch (e: SecurityException) {
                tracker.trackError(e)
                tracker.trackEvent(
                    "clear_all_ringtone_uris_failed_security",
                    mapOf("stage" to "contacts")
                )
            } catch (e: Exception) {
                tracker.trackError(e)
                tracker.trackEvent(
                    "clear_all_ringtone_uris_failed_unexpected",
                    mapOf(
                        "stage" to "contacts",
                        "error_type" to e::class.java.simpleName
                    )
                )
            }
        } else {
            tracker.trackEvent("clear_all_ringtone_uris_contacts_empty")
        }

        // 2. Clear ringtones for ALL raw contacts (no account type filtering)
        val rawContactIds = getAllRawContactIds()
        tracker.trackEvent(
            "clear_all_ringtone_uris_raw_contacts_loaded",
            mapOf("count" to rawContactIds.size.toString())
        )

        if (rawContactIds.isNotEmpty()) {
            val rawContactSelection =
                "${ContactsContract.RawContacts._ID} IN (${rawContactIds.joinToString()})"
            val rawContactValues =
                ContentValues().apply { putNull(ContactsContract.Contacts.CUSTOM_RINGTONE) }

            try {
                val rawContactRowsUpdated = appContext.contentResolver.update(
                    ContactsContract.RawContacts.CONTENT_URI,
                    rawContactValues,
                    rawContactSelection,
                    null
                )
                tracker.trackEvent(
                    "clear_all_ringtone_uris_raw_contacts_updated",
                    mapOf("clearedCount" to rawContactRowsUpdated.toString())
                )
            } catch (e: SecurityException) {
                tracker.trackError(e)
                tracker.trackEvent(
                    "clear_all_ringtone_uris_failed_security",
                    mapOf("stage" to "raw_contacts")
                )
            } catch (e: Exception) {
                tracker.trackError(e)
                tracker.trackEvent(
                    "clear_all_ringtone_uris_failed_unexpected",
                    mapOf(
                        "stage" to "raw_contacts",
                        "error_type" to e::class.java.simpleName
                    )
                )
            }
        } else {
            tracker.trackEvent("clear_all_ringtone_uris_raw_contacts_empty")
        }
    }

    /**
     * Get ALL contact IDs (NO filtering by account type)
     */
    private fun getAllContactIds(): List<Long> {
        val contactIds = mutableListOf<Long>()
        val uri = ContactsContract.Contacts.CONTENT_URI
        val projection = arrayOf(ContactsContract.Contacts._ID)

        appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val contactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            while (cursor.moveToNext()) {
                contactIds.add(cursor.getLong(contactIdIndex))
            }
        }

        return contactIds
    }

    /**
     * Get ALL raw contact IDs (NO filtering by account type)
     */
    private fun getAllRawContactIds(): List<Long> {
        val rawContactIds = mutableListOf<Long>()
        val uri = ContactsContract.RawContacts.CONTENT_URI
        val projection = arrayOf(ContactsContract.RawContacts._ID)

        appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val rawContactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
            while (cursor.moveToNext()) {
                rawContactIds.add(cursor.getLong(rawContactIdIndex))
            }
        }

        return rawContactIds
    }

    fun createLabel(labelName: String, accountId: AccountId?): LabelItem? {
        tracker.trackEvent("createLabel called", mapOf("labelName" to labelName))

        val contentValues = ContentValues().apply {
            put(ContactsContract.Groups.TITLE, labelName)

            if (accountId != null) {
                put(ContactsContract.Groups.ACCOUNT_NAME, accountId.name)
                put(ContactsContract.Groups.ACCOUNT_TYPE, accountId.type)
            }
        }

        val labelUri =
            appContext.contentResolver.insert(ContactsContract.Groups.CONTENT_URI, contentValues)

        return labelUri?.let {
            val labelId = ContentUris.parseId(it)

            tracker.trackEvent(
                "createLabel successful",
                mapOf("labelId" to labelId.toString(), "labelName" to labelName)
            )

            triggerSyncForAllAccounts()

            LabelItem(
                id = providerGroupKey(labelId),
                groupName = labelName,
                contacts = emptyList(),
                ringtoneUriList = emptyList(),
                ringtoneFileName = "",
                storageKind = LabelStorageKind.PROVIDER_GROUP,
                providerGroupId = labelId
            )
        }
    }

    suspend fun getDisplayNamesForContactsBatched(
        contactIds: List<Long>,
        batchSize: Int = 200,
    ): Map<Long, String> = withContext(DispatchersProvider.io) {
        if (contactIds.isEmpty()) return@withContext emptyMap()
        val resolver = appContext.contentResolver
        val out = HashMap<Long, String>(contactIds.size)

        contactIds.chunked(batchSize).forEach { chunk ->
            val selection = "${ContactsContract.Contacts._ID} IN (${chunk.joinToString(",")})"
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            )
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection, selection, null, null
            )?.use { c ->
                val idxId = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val idxName =
                    c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                while (c.moveToNext()) {
                    val id = c.getLong(idxId)
                    val name = c.getString(idxName) ?: ""
                    if (name.isNotBlank()) out[id] = name
                }
            }
        }
        out
    }

    suspend fun getPrimaryPhonesForContactsBatched(
        contactIds: List<Long>,
        batchSize: Int = 200,
    ): Map<Long, String?> = withContext(DispatchersProvider.io) {
        if (contactIds.isEmpty()) return@withContext emptyMap()
        val resolver = appContext.contentResolver
        val out = HashMap<Long, String?>()

        contactIds.chunked(batchSize).forEach { chunk ->
            val selection =
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} IN (${chunk.joinToString(",")})"
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
            )
            val sortOrder =
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} ASC, " +
                        "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, " +
                        "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC, " +
                        "${ContactsContract.CommonDataKinds.Phone._ID} ASC"

            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, selection, null, sortOrder
            )?.use { c ->
                val idxId =
                    c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val idxNum = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val id = c.getLong(idxId)
                    if (out.containsKey(id)) continue
                    out[id] = c.getString(idxNum)
                }
            }
        }
        out
    }

    private fun triggerSyncForAllAccounts() {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true)
        }
        try {
            ContentResolver.requestSync(null, ContactsContract.AUTHORITY, extras)
            tracker.trackEvent("Sync successful")
        } catch (t: Throwable) {
            tracker.trackError(t)
        }
    }

    private fun removeAllContactsFromLabel(labelId: Long) {
        tracker.trackEvent(
            "removeAllContactsFromLabel called",
            mapOf("labelId" to labelId.toString())
        )

        // Query all contacts associated with this label
        val uri = ContactsContract.Data.CONTENT_URI
        val selection =
            "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ? AND " +
                    "${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(
            labelId.toString(),
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
        )

        val rowsDeleted = appContext.contentResolver.delete(uri, selection, selectionArgs)

        triggerSyncForAllAccounts()
        tracker.trackEvent(
            "removeAllContactsFromLabel completed",
            mapOf("labelId" to labelId.toString(), "removedCount" to rowsDeleted.toString())
        )
    }

    private fun applyOperationsInChunks(
        operations: List<ContentProviderOperation>,
        operationContext: String,
        metadata: Map<String, String>,
    ): BatchApplyOutcome {
        if (operations.isEmpty()) {
            return BatchApplyOutcome(
                chunkCount = 0,
                appliedResultCount = 0
            )
        }

        val chunks = chunkBySize(
            items = operations,
            maxChunkSize = MAX_CONTENT_PROVIDER_OPERATIONS_PER_BATCH
        )

        var totalAppliedResults = 0
        chunks.forEachIndexed { index, chunk ->
            val result = runCatching {
                appContext.contentResolver.applyBatch(
                    ContactsContract.AUTHORITY,
                    ArrayList(chunk)
                )
            }.onFailure { error ->
                tracker.trackEvent(
                    "content_provider_batch_failed",
                    metadata + mapOf(
                        "context" to operationContext,
                        "chunkIndex" to (index + 1).toString(),
                        "chunkCount" to chunks.size.toString(),
                        "chunkSize" to chunk.size.toString(),
                        "operationsCount" to operations.size.toString(),
                        "errorType" to error::class.java.simpleName
                    )
                )
            }.getOrThrow()

            totalAppliedResults += result.size
        }

        return BatchApplyOutcome(
            chunkCount = chunks.size,
            appliedResultCount = totalAppliedResults
        )
    }

    private fun isContactAlreadyAssignedToLabel(labelId: Long, contactId: Long): Boolean {
        val projection = arrayOf(ContactsContract.Data._ID)
        val selection =
            "${ContactsContract.CommonDataKinds.GroupMembership.CONTACT_ID} = ? AND " +
                    "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ? AND " +
                    "${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(
            contactId.toString(),
            labelId.toString(),
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
        )

        appContext.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            return cursor.moveToFirst()
        }

        return false
    }

    private fun resolveRawContactIdForTargetInsert(
        contactId: Long,
        appVisibleEditableLabelIds: Set<Long>,
    ): Long? {
        val preferredRawId = getRawContactIdForContactInGroups(
            contactId = contactId,
            appVisibleEditableLabelIds = appVisibleEditableLabelIds
        )
        if (preferredRawId != null) {
            return preferredRawId
        }
        return getRawContactIdForContact(contactId)
    }

    private fun getRawContactIdForContactInGroups(
        contactId: Long,
        appVisibleEditableLabelIds: Set<Long>,
    ): Long? {
        val groupIds = appVisibleEditableLabelIds.distinct()
        if (groupIds.isEmpty()) return null

        val projection = arrayOf(ContactsContract.Data.RAW_CONTACT_ID)
        val groupPlaceholders = groupIds.joinToString(",") { "?" }
        val selection =
            "${ContactsContract.CommonDataKinds.GroupMembership.CONTACT_ID} = ? AND " +
                    "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} IN ($groupPlaceholders) AND " +
                    "${ContactsContract.Data.MIMETYPE} = ? AND " +
                    "${ContactsContract.Data.RAW_CONTACT_ID} IS NOT NULL"
        val selectionArgs = (listOf(contactId.toString()) +
                groupIds.map { it.toString() } +
                ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
            .toTypedArray()

        appContext.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(
                    cursor.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
                )
            }
        }
        return null
    }

    private fun getRawContactIdForContact(contactId: Long): Long? {
        val uri = ContactsContract.RawContacts.CONTENT_URI
        val projection = arrayOf(ContactsContract.RawContacts._ID)
        val selection = "${ContactsContract.RawContacts.CONTACT_ID} = ? AND " +
                "${ContactsContract.RawContacts.DELETED} = 0"
        val selectionArgs = arrayOf(contactId.toString())

        appContext.contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            "${ContactsContract.RawContacts._ID} ASC"
        )
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID))
                }
            }
        return null
    }

    private suspend fun buildProviderLabelItem(
        id: Long,
        title: String,
        isReadOnly: Boolean,
        isDeleted: Boolean,
        systemId: String?,
    ): LabelItem {
        val canDelete = resolveGroupCanDelete(
            isReadOnly = isReadOnly,
            isDeleted = isDeleted,
            systemId = systemId
        )
        val contacts = getContactsForLabel(id)
        val ringtoneUris = contacts.mapNotNull { it.ringtoneUriStr }.distinct()
        val ringtoneFileName = ringtoneUris.stringifyContacts(preferenceHelper)
        return LabelItem(
            id = providerGroupKey(id),
            groupName = title,
            contacts = contacts,
            ringtoneUriList = ringtoneUris,
            ringtoneFileName = ringtoneFileName,
            canDelete = canDelete,
            storageKind = LabelStorageKind.PROVIDER_GROUP,
            providerGroupId = id
        )
    }

    private suspend fun getContactsForLabel(labelId: Long): List<Contact> {
        val rows = mutableListOf<ContactRow>()
        val uri = ContactsContract.Data.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.GroupMembership.CONTACT_ID,
            ContactsContract.Data.LOOKUP_KEY,
            ContactsContract.Data.DISPLAY_NAME_PRIMARY
        )
        val selection =
            "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(
            labelId.toString(),
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
        )

        appContext.contentResolver.query(uri, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                val contactIdIndex =
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.GroupMembership.CONTACT_ID)
                val lookupKeyIndex =
                    cursor.getColumnIndexOrThrow(ContactsContract.Data.LOOKUP_KEY)
                val nameIndex =
                    cursor.getColumnIndexOrThrow(ContactsContract.Data.DISPLAY_NAME_PRIMARY)

                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(contactIdIndex)
                    val lookupKey = cursor.getString(lookupKeyIndex).orEmpty()
                    val name = cursor.getString(nameIndex).orEmpty()
                    rows.add(
                        ContactRow(
                            id = contactId,
                            lookupKey = lookupKey,
                            name = name,
                            ringtoneUriStr = null
                        )
                    )
                }
            }

        val contactIds = rows.map { it.id }
        val phoneByContactId = getPrimaryPhonesForContactsBatched(contactIds)
        val ringtoneByContactId = getRingtonesForContactsBatched(contactIds)
        return rows.map { row ->
            row.toContact(
                phoneByContactId = phoneByContactId,
                ringtoneByContactId = ringtoneByContactId
            )
        }
    }

    suspend fun getAllLabelItemsForAccounts(selectedAccounts: AccountId): List<LabelItem> =
        withContext(DispatchersProvider.io) {
            val pairs = listOf(selectedAccounts.type to selectedAccounts.name)
            if (pairs.isEmpty()) return@withContext getAllLabelItems()

            val where = buildString {
                append("${ContactsContract.Groups.DELETED}=0 AND ")
                append("${ContactsContract.Groups.GROUP_IS_READ_ONLY}=0 AND (")
                pairs.forEachIndexed { idx, _ ->
                    if (idx > 0) append(" OR ")
                    append("(${ContactsContract.Groups.ACCOUNT_TYPE}=? AND ${ContactsContract.Groups.ACCOUNT_NAME}=?)")
                }
                append(")")
            }
            val args = pairs.flatMap { listOf(it.first, it.second) }.toTypedArray()

            val labels = mutableListOf<LabelItem>()
            val cr = appContext.contentResolver
            cr.query(
                ContactsContract.Groups.CONTENT_URI,
                GROUPS_LIST_PROJECTION,
                where,
                args,
                null
            )?.use { c ->
                val idxId = c.getColumnIndexOrThrow(ContactsContract.Groups._ID)
                val idxTitle = c.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)
                val idxReadOnly =
                    c.getColumnIndexOrThrow(ContactsContract.Groups.GROUP_IS_READ_ONLY)
                val idxDeleted = c.getColumnIndexOrThrow(ContactsContract.Groups.DELETED)
                val idxSystemId = c.getColumnIndexOrThrow(ContactsContract.Groups.SYSTEM_ID)
                while (c.moveToNext()) {
                    val title = c.getString(idxTitle).orEmpty()
                    if (title.isBlank()) continue
                    labels.add(
                        buildProviderLabelItem(
                            id = c.getLong(idxId),
                            title = title,
                            isReadOnly = c.getInt(idxReadOnly) != 0,
                            isDeleted = c.getInt(idxDeleted) != 0,
                            systemId = c.getString(idxSystemId)
                        )
                    )
                }
            }

            return@withContext labels
        }

    suspend fun getLookupKeysForContactIdsBatched(
        contactIds: List<Long>,
        batchSize: Int = 200,
    ): Map<Long, String> = withContext(DispatchersProvider.io) {
        if (contactIds.isEmpty()) return@withContext emptyMap()
        val resolver = appContext.contentResolver
        val out = HashMap<Long, String>(contactIds.size)

        contactIds.chunked(batchSize).forEach { chunk ->
            val selection = "${ContactsContract.Contacts._ID} IN (${chunk.joinToString(",")})"
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.LOOKUP_KEY
                ),
                selection,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val lookupKeyIndex =
                    cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(idIndex)
                    val lookupKey = cursor.getString(lookupKeyIndex).orEmpty()
                    if (lookupKey.isNotBlank()) {
                        out[contactId] = lookupKey
                    }
                }
            }
        }
        out
    }

    suspend fun getContactsByLookupKeys(
        lookupKeys: List<String>,
        batchSize: Int = 200,
    ): List<Contact> = withContext(DispatchersProvider.io) {
        if (lookupKeys.isEmpty()) return@withContext emptyList()
        val byLookupKey = queryContactRowsByLookupKeys(lookupKeys, batchSize)
        val phoneByContactId = getPrimaryPhonesForContactsBatched(
            byLookupKey.values.map { row -> row.id }
        )
        return@withContext lookupKeys.mapNotNull { lookupKey ->
            byLookupKey[lookupKey]?.toContact(phoneByContactId)
        }
    }

    private fun queryContactRowsByLookupKeys(
        lookupKeys: List<String>,
        batchSize: Int,
    ): Map<String, ContactRow> {
        val byLookupKey = linkedMapOf<String, ContactRow>()

        lookupKeys.distinct().chunked(batchSize).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val selection = "${ContactsContract.Contacts.LOOKUP_KEY} IN ($placeholders)"
            appContext.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                CONTACTS_LIST_PROJECTION,
                selection,
                chunk.toTypedArray(),
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val lookupKeyIdx =
                    cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                val nameIdx =
                    cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val toneIdx =
                    cursor.getColumnIndexOrThrow(ContactsContract.Contacts.CUSTOM_RINGTONE)
                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(idIdx)
                    val lookupKey = cursor.getString(lookupKeyIdx).orEmpty()
                    byLookupKey[lookupKey] = ContactRow(
                        id = contactId,
                        lookupKey = lookupKey,
                        name = cursor.getString(nameIdx).orEmpty(),
                        ringtoneUriStr = cursor.getString(toneIdx)
                    )
                }
            }
        }
        return byLookupKey
    }

    private suspend fun queryContactsByIds(contactIds: List<Long>): List<Contact> =
        withContext(DispatchersProvider.io) {
            val rows = mutableListOf<ContactRow>()
            contactIds.chunked(900).forEach { batch ->
                val placeholders = batch.joinToString(",") { "?" }
                val selection = "${ContactsContract.Contacts._ID} IN ($placeholders)"
                val args = batch.map { it.toString() }.toTypedArray()
                appContext.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    CONTACTS_LIST_PROJECTION,
                    selection,
                    args,
                    null
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                    val lookupKeyIdx =
                        cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                    val nameIdx =
                        cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                    val toneIdx =
                        cursor.getColumnIndexOrThrow(ContactsContract.Contacts.CUSTOM_RINGTONE)
                    while (cursor.moveToNext()) {
                        val contactId = cursor.getLong(idIdx)
                        rows += ContactRow(
                            id = contactId,
                            lookupKey = cursor.getString(lookupKeyIdx).orEmpty(),
                            name = cursor.getString(nameIdx).orEmpty(),
                            ringtoneUriStr = cursor.getString(toneIdx)
                        )
                    }
                }
            }
            val phoneByContactId = getPrimaryPhonesForContactsBatched(rows.map { it.id })
            rows.map { row -> row.toContact(phoneByContactId) }
        }

    private fun pureLocalContactIds(): Set<Long> {
        val rawContacts = getRawContactDescriptors()
        if (rawContacts.isEmpty()) return emptySet()
        return pureLocalContactIds(
            rawContacts = rawContacts,
            detectionContext = buildLocalContactDetectionContext()
        )
    }

    private fun pureLocalContactIds(
        rawContacts: List<RawContactDescriptor>,
        detectionContext: LocalContactDetectionContext,
    ): Set<Long> {
        return rawContacts
            .groupBy { it.contactId }
            .filterValues { descriptors ->
                isOnDeviceEligibleContact(descriptors, detectionContext)
            }
            .keys
    }

    private fun isOnDeviceEligibleContact(
        descriptors: List<RawContactDescriptor>,
        detectionContext: LocalContactDetectionContext,
    ): Boolean {
        if (descriptors.isEmpty()) return false

        val classifications = descriptors.map { descriptor ->
            classifyLocalRawContact(
                descriptor = descriptor,
                detectionContext = detectionContext
            )
        }
        val hasLocalLike = classifications.any { classification -> classification.isLocal }
        if (!hasLocalLike) return false

        return classifications.all { classification ->
            classification.isLocal || classification.communicationLike
        }
    }

    private fun getRawContactDescriptors(): List<RawContactDescriptor> {
        val descriptors = mutableListOf<RawContactDescriptor>()
        appContext.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            RAW_CONTACTS_PROJECTION,
            "${ContactsContract.RawContacts.DELETED} = 0",
            null,
            null
        )?.use { cursor ->
            val rawIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
            val contactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)
            val accountNameIndex =
                cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_NAME)
            val accountTypeIndex =
                cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
            while (cursor.moveToNext()) {
                descriptors += RawContactDescriptor(
                    rawContactId = cursor.getLong(rawIdIndex),
                    contactId = cursor.getLong(contactIdIndex),
                    accountName = cursor.getString(accountNameIndex),
                    accountType = cursor.getString(accountTypeIndex)
                )
            }
        }
        return descriptors
    }

    private fun isLocalRawContact(descriptor: RawContactDescriptor): Boolean {
        return isLocalRawContact(
            descriptor = descriptor,
            detectionContext = buildLocalContactDetectionContext()
        )
    }

    private fun isLocalRawContact(
        descriptor: RawContactDescriptor,
        detectionContext: LocalContactDetectionContext,
    ): Boolean =
        classifyLocalRawContact(
            descriptor = descriptor,
            detectionContext = detectionContext
        ).isLocal

    private fun classifyLocalRawContact(
        descriptor: RawContactDescriptor,
        detectionContext: LocalContactDetectionContext,
    ): LocalRawContactClassification {
        val localType = detectionContext.localType
        val localName = detectionContext.localName

        val accountType = descriptor.accountType?.trim()
        val accountName = descriptor.accountName?.trim()

        val officialAndroidLocal = if (!localType.isNullOrBlank() && !localName.isNullOrBlank()) {
            accountType == localType && accountName == localName
        } else {
            accountType.isNullOrBlank() && accountName.isNullOrBlank()
        }

        if (officialAndroidLocal) {
            return LocalRawContactClassification(
                isLocal = true,
                communicationLike = false
            )
        }

        val hasAccountPair = !accountType.isNullOrBlank() || !accountName.isNullOrBlank()
        if (!hasAccountPair) {
            return LocalRawContactClassification(
                isLocal = true,
                communicationLike = false
            )
        }

        val hasContactsSyncAdapter =
            accountType in detectionContext.contactSyncAccountTypes
        val hasMatchingAccountManagerAccount =
            LocalContactAccountKey(
                accountType,
                accountName
            ) in detectionContext.accountManagerAccounts
        val simLike = accountType.looksLikeSimSource() || accountName.looksLikeSimSource()
        val cloudLike = accountType.looksLikeCloudSource() || accountName.looksLikeCloudSource()
        val communicationLike =
            accountType.looksLikeCommunicationSource() ||
                    accountName.looksLikeCommunicationSource()
        val managedExternalAccount =
            hasContactsSyncAdapter && hasMatchingAccountManagerAccount

        val deviceManagedLocalLike =
            !managedExternalAccount &&
                    !simLike &&
                    !cloudLike &&
                    !communicationLike

        return LocalRawContactClassification(
            isLocal = deviceManagedLocalLike,
            communicationLike = communicationLike
        )
    }

    private fun buildLocalContactDetectionContext(): LocalContactDetectionContext {
        val localType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ContactsContract.RawContacts.getLocalAccountType(appContext)
        } else {
            null
        }
        val localName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ContactsContract.RawContacts.getLocalAccountName(appContext)
        } else {
            null
        }

        val contactSyncAccountTypes = ContentResolver.getSyncAdapterTypes()
            .asSequence()
            .filter { syncAdapter -> syncAdapter.authority == ContactsContract.AUTHORITY }
            .map { syncAdapter -> syncAdapter.accountType }
            .toSet()

        val accountManagerAccounts = AccountManager.get(appContext).accounts
            .asSequence()
            .map { account ->
                LocalContactAccountKey(
                    accountType = account.type,
                    accountName = account.name
                )
            }
            .toSet()

        return LocalContactDetectionContext(
            localType = localType,
            localName = localName,
            contactSyncAccountTypes = contactSyncAccountTypes,
            accountManagerAccounts = accountManagerAccounts
        )
    }

    private fun String?.looksLikeSimSource(): Boolean {
        if (this.isNullOrBlank()) return false

        val value = lowercase()
        val simTokenRegex = Regex("(^|[._\\s:+\\-])(sim|usim|isim|icc|uicc)($|[._\\s:+\\-])")

        return simTokenRegex.containsMatchIn(value) || value.contains("esim")
    }

    private fun String?.looksLikeCloudSource(): Boolean {
        if (this.isNullOrBlank()) return false

        val value = lowercase()

        return value.contains("@") ||
                value.contains("google") ||
                value.contains("gmail") ||
                value.contains("outlook") ||
                value.contains("exchange") ||
                value.contains("microsoft")
    }

    private fun String?.looksLikeCommunicationSource(): Boolean {
        if (this.isNullOrBlank()) return false

        val value = lowercase()

        return value.contains("whatsapp") ||
                value.contains("viber") ||
                value.contains("telegram") ||
                value.contains("signal") ||
                value.contains("facebook") ||
                value.contains("messenger") ||
                value.contains("skype") ||
                value.contains("tachyon") ||
                value.contains("meet") ||
                value.contains("duo") ||
                value.contains("line") ||
                value.contains("wechat")
    }

    private fun providerGroupKey(groupId: Long): String = "group:$groupId"

    private fun ringtoneUriSignature(uri: String): String {
        if (uri.isBlank()) return "empty"
        return uri.hashCode().toUInt().toString(16)
    }
}

internal data class GroupDeletionCapability(
    val isDeleted: Boolean,
    val isReadOnly: Boolean,
    val systemId: String?,
)

private data class LocalContactAccountKey(
    val accountType: String?,
    val accountName: String?,
)

private data class LocalContactDetectionContext(
    val localType: String?,
    val localName: String?,
    val contactSyncAccountTypes: Set<String>,
    val accountManagerAccounts: Set<LocalContactAccountKey>,
)

private data class LocalRawContactClassification(
    val isLocal: Boolean,
    val communicationLike: Boolean,
)

internal fun canDeleteGroup(capability: GroupDeletionCapability): Boolean {
    if (capability.isDeleted) return false
    if (capability.isReadOnly) return false
    if (!capability.systemId.isNullOrBlank()) return false
    return true
}

private fun List<String>.stringifyContacts(preferenceHelper: EncryptedPreferencesHelper): String =
    takeIf { it.isNotEmpty() }
        ?.mapNotNull { preferenceHelper.getString(it) }
        ?.filter { it.isNotBlank() }
        ?.joinToString()
        .orEmpty()
