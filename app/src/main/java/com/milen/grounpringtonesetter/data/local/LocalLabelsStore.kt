package com.milen.grounpringtonesetter.data.local

import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val KEY_LOCAL_LABELS_DOCUMENT = "local_labels_document_v1"
private const val VERSION_PREFIX = "v"
private const val LABEL_PREFIX = "l"
private const val MEMBER_PREFIX = "m"
private const val FIELD_SEPARATOR = "|"

internal interface LocalLabelsDataSource {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun remove(key: String)
}

internal class EncryptedLocalLabelsDataSource(
    private val prefs: EncryptedPreferencesHelper,
) : LocalLabelsDataSource {
    override suspend fun read(key: String): String? = prefs.getStringAsync(key)

    override suspend fun write(key: String, value: String) = prefs.saveStringAsync(key, value)

    override suspend fun remove(key: String) = prefs.removeAsync(key)
}

internal class LocalLabelsStore(
    private val dataSource: LocalLabelsDataSource,
) {
    suspend fun read(): LocalLabelDocument {
        val raw = dataSource.read(KEY_LOCAL_LABELS_DOCUMENT)?.trim().orEmpty()
        if (raw.isBlank()) return LocalLabelDocument()
        return deserialize(raw)
    }

    suspend fun write(document: LocalLabelDocument) {
        if (document.labels.isEmpty()) {
            dataSource.remove(KEY_LOCAL_LABELS_DOCUMENT)
            return
        }
        dataSource.write(KEY_LOCAL_LABELS_DOCUMENT, serialize(document))
    }

    internal fun serialize(document: LocalLabelDocument): String = buildString {
        append(VERSION_PREFIX)
        append(FIELD_SEPARATOR)
        append(document.version)
        document.labels.forEach { label ->
            append('\n')
            append(LABEL_PREFIX)
            append(FIELD_SEPARATOR)
            append(encode(label.id))
            append(FIELD_SEPARATOR)
            append(encode(label.name))
            label.members.forEach { member ->
                append('\n')
                append(MEMBER_PREFIX)
                append(FIELD_SEPARATOR)
                append(encode(label.id))
                append(FIELD_SEPARATOR)
                append(encode(member.lookupKey))
                append(FIELD_SEPARATOR)
                append(member.contactId)
            }
        }
    }

    internal fun deserialize(raw: String): LocalLabelDocument {
        val labels = linkedMapOf<String, MutableLocalStoredLabel>()
        var version = LocalLabelDocument.CURRENT_VERSION

        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val parts = line.split(FIELD_SEPARATOR)
                when (parts.firstOrNull()) {
                    VERSION_PREFIX -> {
                        version = parts.getOrNull(1)?.toIntOrNull() ?: LocalLabelDocument.CURRENT_VERSION
                    }

                    LABEL_PREFIX -> {
                        val id = parts.getOrNull(1)?.let(::decode).orEmpty()
                        if (id.isBlank()) return@forEach
                        val name = parts.getOrNull(2)?.let(::decode).orEmpty()
                        labels.putIfAbsent(
                            id,
                            MutableLocalStoredLabel(
                                id = id,
                                name = name,
                                members = linkedMapOf()
                            )
                        )
                    }

                    MEMBER_PREFIX -> {
                        val labelId = parts.getOrNull(1)?.let(::decode).orEmpty()
                        val lookupKey = parts.getOrNull(2)?.let(::decode).orEmpty()
                        val contactId = parts.getOrNull(3)?.toLongOrNull() ?: return@forEach
                        if (labelId.isBlank() || lookupKey.isBlank()) return@forEach
                        val label = labels.getOrPut(
                            labelId
                        ) {
                            MutableLocalStoredLabel(
                                id = labelId,
                                name = "",
                                members = linkedMapOf()
                            )
                        }
                        label.members.putIfAbsent(
                            lookupKey,
                            LocalStoredLabelMember(
                                lookupKey = lookupKey,
                                contactId = contactId
                            )
                        )
                    }
                }
            }

        return LocalLabelDocument(
            version = version,
            labels = labels.values.map { label ->
                LocalStoredLabel(
                    id = label.id,
                    name = label.name,
                    members = label.members.values.toList()
                )
            }
        )
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

private data class MutableLocalStoredLabel(
    val id: String,
    val name: String,
    val members: LinkedHashMap<String, LocalStoredLabelMember>,
)
