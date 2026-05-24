package com.milen.grounpringtonesetter.ui.home

internal object HomeGroupCardAccessibilityText {

    fun resolveRingtoneText(
        rawRingtoneText: String,
        noRingtoneLabel: String,
    ): String = rawRingtoneText.trim().ifBlank { noRingtoneLabel }

    fun buildContactsAccessibilityText(
        contactsLabel: String,
        contactsCount: Int,
    ): String = "$contactsLabel $contactsCount"

    fun buildRingtoneDisplayText(
        ringtoneLabel: String,
        ringtoneText: String,
        noRingtoneLabel: String,
    ): String =
        if (ringtoneText == noRingtoneLabel) noRingtoneLabel
        else "$ringtoneLabel: $ringtoneText"
}
