package com.milen.grounpringtonesetter.backup

internal class RestorePlanner {

    fun plan(snapshot: GrsBackupSnapshot): RestorePlan {
        val toneById = snapshot.tones.associateBy { tone -> tone.id }
        val groupTones = snapshot.groupTones.mapNotNull { groupTone ->
            val tone = toneById[groupTone.toneId] ?: return@mapNotNull null
            PlannedGroupToneRestore(
                groupName = groupTone.groupName,
                toneId = groupTone.toneId,
                toneDisplayName = tone.displayName
            )
        }
        return RestorePlan(
            snapshot = snapshot,
            groupTones = groupTones
        )
    }
}
