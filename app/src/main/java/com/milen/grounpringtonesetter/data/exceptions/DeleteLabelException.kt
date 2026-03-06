package com.milen.grounpringtonesetter.data.exceptions

internal enum class DeleteLabelFailureReason {
    GROUP_PROTECTED,
    DELETE_FAILED,
}

internal class DeleteLabelException(
    val labelId: Long,
    val reason: DeleteLabelFailureReason,
) : Exception("Delete label failed: labelId=$labelId reason=$reason")
