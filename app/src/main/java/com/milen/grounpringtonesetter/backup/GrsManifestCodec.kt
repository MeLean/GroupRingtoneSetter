package com.milen.grounpringtonesetter.backup

import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneType
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

internal object GrsManifestCodec {

    fun encode(snapshot: GrsBackupSnapshot): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<grsBackup formatId="$GRS_FORMAT_ID" schemaVersion="$GRS_SCHEMA_VERSION">""")
        textElement("createdAtEpochMillis", snapshot.createdAtEpochMillis.toString())
        textElement("appVersionName", snapshot.appVersionName)
        textElement("sourceLabel", snapshot.sourceLabel)
        appendList("groupTones", "groupTone", snapshot.groupTones) { groupTone ->
            textElement("groupName", groupTone.groupName)
            textElement("toneId", groupTone.toneId)
        }
        appendList("defaultTones", "defaultTone", snapshot.defaultTones) { defaultTone ->
            textElement("type", defaultTone.type.name)
            textElement("toneId", defaultTone.toneId)
            textElement("displayName", defaultTone.displayName)
        }
        appendList("tones", "tone", snapshot.tones) { tone ->
            textElement("id", tone.id)
            textElement("displayName", tone.displayName)
            textElement("category", tone.category.name)
            textElement("archivePath", tone.archivePath)
            optionalTextElement("sizeBytes", tone.sizeBytes?.toString())
        }
        append("</grsBackup>")
    }

    fun decode(raw: String): GrsBackupSnapshot {
        val root = parseRoot(raw)
        if (root.tagName != "grsBackup" || root.getAttribute("formatId") != GRS_FORMAT_ID) {
            throw GrsArchiveException("Unsupported backup format")
        }
        val schemaVersion = root.getAttribute("schemaVersion").toIntOrNull() ?: 0
        if (schemaVersion != GRS_SCHEMA_VERSION) {
            throw GrsArchiveException("Unsupported backup version")
        }
        return GrsBackupSnapshot(
            createdAtEpochMillis = root.childText("createdAtEpochMillis")?.toLongOrNull() ?: 0L,
            appVersionName = root.childText("appVersionName").orEmpty(),
            sourceLabel = root.childText("sourceLabel").orEmpty(),
            groupTones = root.childElements("groupTones", "groupTone").map(::groupToneFromElement),
            defaultTones = root.childElements("defaultTones", "defaultTone").map(::defaultToneFromElement),
            tones = root.childElements("tones", "tone").map(::toneFromElement)
        )
    }

    private fun groupToneFromElement(element: Element): GrsGroupToneSnapshot =
        GrsGroupToneSnapshot(
            groupName = element.childText("groupName").orEmpty(),
            toneId = element.childText("toneId").orEmpty()
        )

    private fun defaultToneFromElement(element: Element): GrsDefaultToneSnapshot =
        GrsDefaultToneSnapshot(
            type = DeviceDefaultToneType.valueOf(element.childText("type").orEmpty()),
            toneId = element.childText("toneId").orEmpty(),
            displayName = element.childText("displayName").orEmpty()
        )

    private fun toneFromElement(element: Element): GrsToneSnapshot =
        GrsToneSnapshot(
            id = element.childText("id").orEmpty(),
            displayName = element.childText("displayName").orEmpty(),
            category = GrsToneCategory.valueOf(element.childText("category").orEmpty()),
            archivePath = element.childText("archivePath").orEmpty(),
            sizeBytes = element.childText("sizeBytes")?.toLongOrNull()
        )

    private fun <T> StringBuilder.appendList(
        containerName: String,
        itemName: String,
        items: List<T>,
        appendItem: StringBuilder.(T) -> Unit,
    ) {
        append("<")
        append(containerName)
        append(">")
        items.forEach { item ->
            append("<")
            append(itemName)
            append(">")
            appendItem(item)
            append("</")
            append(itemName)
            append(">")
        }
        append("</")
        append(containerName)
        append(">")
    }

    private fun StringBuilder.textElement(name: String, value: String) {
        append("<")
        append(name)
        append(">")
        append(value.escapeXml())
        append("</")
        append(name)
        append(">")
    }

    private fun StringBuilder.optionalTextElement(name: String, value: String?) {
        value?.let { textElement(name, it) }
    }

    private fun parseRoot(raw: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isIgnoringComments = true
            safeDisableXInclude()
            safeSetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            safeSetFeature("http://xml.org/sax/features/external-general-entities", false)
            safeSetFeature("http://xml.org/sax/features/external-parameter-entities", false)
            safeSetFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val document = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(raw.cleanedXmlText().toByteArray(Charsets.UTF_8)))
        document.documentElement.normalize()
        return document.documentElement
    }

    private fun DocumentBuilderFactory.safeSetFeature(
        name: String,
        value: Boolean,
    ) {
        runCatching { setFeature(name, value) }
    }

    private fun DocumentBuilderFactory.safeDisableXInclude() {
        runCatching { isXIncludeAware = false }
    }

    private fun Element.childElement(name: String): Element? {
        val nodes = childNodes
        repeat(nodes.length) { index ->
            val element = nodes.item(index) as? Element ?: return@repeat
            if (element.tagName == name) return element
        }
        return null
    }

    private fun Element.childElements(containerName: String, itemName: String): List<Element> {
        val container = childElement(containerName) ?: return emptyList()
        val nodes = container.childNodes
        return buildList {
            repeat(nodes.length) { index ->
                val element = nodes.item(index) as? Element ?: return@repeat
                if (element.tagName == itemName) add(element)
            }
        }
    }

    private fun Element.childText(name: String): String? =
        childElement(name)?.textContent?.takeIf { it.isNotBlank() }

    private fun String.escapeXml(): String =
        cleanedXmlText()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun String.cleanedXmlText(): String {
        val builder = StringBuilder(length)
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            if (codePoint.isXmlCharacter()) {
                builder.appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
        return builder.toString()
    }

    private fun Int.isXmlCharacter(): Boolean =
        this == 0x9 ||
                this == 0xA ||
                this == 0xD ||
                this in 0x20..0xD7FF ||
                this in 0xE000..0xFFFD ||
                this in 0x10000..0x10FFFF
}

internal class GrsArchiveException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
