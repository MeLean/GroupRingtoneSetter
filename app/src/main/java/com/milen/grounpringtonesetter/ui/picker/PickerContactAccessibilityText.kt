package com.milen.grounpringtonesetter.ui.picker

internal object PickerContactAccessibilityText {

    fun buildPhoneText(
        phoneLabel: String,
        phoneNumber: String?,
    ): String = phoneNumber
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { "$phoneLabel: $it" }
        .orEmpty()

    fun buildCheckboxDescription(
        isChecked: Boolean,
        contactName: String,
        groupName: String,
        includeTemplate: String,
        removeTemplate: String,
    ): String {
        val normalizedGroupName = groupName.trim()
        if (normalizedGroupName.isBlank()) return contactName

        return if (isChecked) {
            removeTemplate.format(contactName, normalizedGroupName)
        } else {
            includeTemplate.format(contactName, normalizedGroupName)
        }
    }
}
