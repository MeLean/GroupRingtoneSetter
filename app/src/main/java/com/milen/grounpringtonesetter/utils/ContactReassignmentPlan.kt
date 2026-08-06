package com.milen.grounpringtonesetter.utils

internal data class ContactReassignmentPlan(
    val distinctContactIds: List<Long>,
    val rawContactIdsByContactId: Map<Long, Long>,
)

internal fun buildContactReassignmentPlan(
    contactIds: List<Long>,
    isAlreadyAssigned: (Long) -> Boolean,
    resolveRawContactId: (Long) -> Long?,
): ContactReassignmentPlan {
    val distinctContactIds = contactIds.distinct()
    val blockedContactIds = LinkedHashSet<Long>()
    val rawContactIdsByContactId = buildMap {
        distinctContactIds.forEach { contactId ->
            if (!isAlreadyAssigned(contactId)) {
                val rawContactId = resolveRawContactId(contactId)
                if (rawContactId == null) {
                    blockedContactIds.add(contactId)
                } else {
                    put(contactId, rawContactId)
                }
            }
        }
    }
    if (blockedContactIds.isNotEmpty()) {
        throw IllegalStateException(
            "Failed to reassign contacts without raw-contact mapping: " +
                    blockedContactIds.joinToString(",")
        )
    }

    return ContactReassignmentPlan(
        distinctContactIds = distinctContactIds,
        rawContactIdsByContactId = rawContactIdsByContactId,
    )
}
