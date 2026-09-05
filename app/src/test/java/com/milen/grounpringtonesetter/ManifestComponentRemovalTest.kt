package com.milen.grounpringtonesetter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ManifestComponentRemovalTest {

    @Test
    fun `WorkManager startup initializer is removed from the application manifest`() {
        val initializer = findMetadata("androidx.work.WorkManagerInitializer")

        assertNotNull(initializer)
        assertEquals("remove", initializer?.getAttributeNS(TOOLS_NAMESPACE, "node"))
    }

    @Test
    fun `WorkManager diagnostics receiver is removed from the application manifest`() {
        val receiver = findComponent(
            elementName = "receiver",
            className = "androidx.work.impl.diagnostics.DiagnosticsReceiver",
        )

        assertNotNull(receiver)
        assertEquals("remove", receiver?.getAttributeNS(TOOLS_NAMESPACE, "node"))
    }

    private fun findComponent(elementName: String, className: String): Element? {
        val manifest = manifestFile()
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)
        val elements = document.getElementsByTagName(elementName)

        return (0 until elements.length)
            .mapNotNull { elements.item(it) as? Element }
            .firstOrNull { it.getAttributeNS(ANDROID_NAMESPACE, "name") == className }
    }

    private fun findMetadata(className: String): Element? =
        findComponent(elementName = "meta-data", className = className)

    private fun manifestFile(): File = listOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
    ).firstOrNull(File::isFile)
        ?: error("Unable to locate the application manifest")

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NAMESPACE = "http://schemas.android.com/tools"
    }
}
