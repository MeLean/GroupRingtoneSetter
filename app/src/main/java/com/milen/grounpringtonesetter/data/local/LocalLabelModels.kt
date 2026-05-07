package com.milen.grounpringtonesetter.data.local

import java.util.UUID

internal data class LocalLabelDocument(
    val version: Int = CURRENT_VERSION,
    val labels: List<LocalStoredLabel> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

internal data class LocalStoredLabel(
    val id: String,
    val name: String,
    val members: List<LocalStoredLabelMember>,
)

internal data class LocalStoredLabelMember(
    val lookupKey: String,
    val contactId: Long,
)

internal data class MirroredLocalLabelAssignment(
    val labelId: String?,
    val labelName: String,
    val lookupKey: String,
    val contactId: Long,
)

internal fun recoverLocalLabels(
    stored: LocalLabelDocument,
    mirrored: List<MirroredLocalLabelAssignment>,
    idGenerator: () -> String = { UUID.randomUUID().toString() },
): LocalLabelDocument {
    if (mirrored.isEmpty()) return stored

    val normalizedMirrored = mirrored.mapNotNull { assignment ->
        val normalizedLabelName = assignment.labelName.trim()
        val normalizedLookupKey = assignment.lookupKey.trim()
        if (normalizedLabelName.isBlank() || normalizedLookupKey.isBlank()) {
            null
        } else {
            assignment.copy(
                labelName = normalizedLabelName,
                lookupKey = normalizedLookupKey
            )
        }
    }
    if (normalizedMirrored.isEmpty()) return stored

    val storedLabelIdByName = linkedMapOf<String, String>()
    stored.labels.forEach { label ->
        val normalizedStoredName = label.name.trim()
        if (normalizedStoredName.isNotBlank()) {
            storedLabelIdByName.putIfAbsent(normalizedStoredName, label.id)
        }
    }
    val existingMembersByLabelId = stored.labels.associate { label ->
        label.id to LinkedHashMap(
            label.members.associateBy { member -> member.lookupKey }
        )
    }.toMutableMap()
    val newLabelsById = linkedMapOf<String, PendingRecoveredLabel>()
    val generatedLabelIdByName = linkedMapOf<String, String>()

    normalizedMirrored.forEach { assignment ->
        val resolvedLabelId = assignment.labelId?.takeIf { it.isNotBlank() }
            ?: storedLabelIdByName[assignment.labelName]
            ?: generatedLabelIdByName.getOrPut(assignment.labelName, idGenerator)
        val member = LocalStoredLabelMember(
            lookupKey = assignment.lookupKey,
            contactId = assignment.contactId
        )

        val existingMembers = existingMembersByLabelId[resolvedLabelId]
        if (existingMembers != null) {
            existingMembers.putIfAbsent(member.lookupKey, member)
        } else {
            val recoveredLabel = newLabelsById.getOrPut(resolvedLabelId) {
                PendingRecoveredLabel(name = assignment.labelName)
            }
            recoveredLabel.members.putIfAbsent(member.lookupKey, member)
        }
    }

    val mergedLabels = stored.labels.map { label ->
        val mergedMembers = existingMembersByLabelId[label.id]?.values?.toList().orEmpty()
        if (mergedMembers == label.members) {
            label
        } else {
            label.copy(members = mergedMembers)
        }
    } + newLabelsById.map { (labelId, recoveredLabel) ->
        LocalStoredLabel(
            id = labelId,
            name = recoveredLabel.name,
            members = recoveredLabel.members.values.toList()
        )
    }

    if (mergedLabels == stored.labels) return stored
    return stored.copy(labels = mergedLabels)
}

private fun buildRecoveredLabels(
    assignments: List<MirroredLocalLabelAssignment>,
    idGenerator: () -> String,
): List<LocalStoredLabel> {
    if (assignments.isEmpty()) return emptyList()

    val generatedByName = linkedMapOf<String, String>()
    val grouped = linkedMapOf<String, MutableList<MirroredLocalLabelAssignment>>()

    assignments.forEach { assignment ->
        val stableId = assignment.labelId?.takeIf { it.isNotBlank() }
            ?: generatedByName.getOrPut(assignment.labelName) { idGenerator() }
        grouped.getOrPut(stableId) { mutableListOf() }.add(assignment)
    }

    return grouped.map { (labelId, entries) ->
        val first = entries.first()
        LocalStoredLabel(
            id = labelId,
            name = first.labelName,
            members = entries
                .distinctBy { it.lookupKey }
                .map { entry ->
                    LocalStoredLabelMember(
                        lookupKey = entry.lookupKey,
                        contactId = entry.contactId
                    )
                }
        )
    }
}

private data class PendingRecoveredLabel(
    val name: String,
    val members: LinkedHashMap<String, LocalStoredLabelMember> = linkedMapOf(),
)
