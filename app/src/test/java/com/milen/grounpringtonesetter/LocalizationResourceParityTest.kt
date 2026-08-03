package com.milen.grounpringtonesetter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationResourceParityTest {

    @Test
    fun `every supported locale defines the complete translatable string set`() {
        val resourceRoot = File("src/main/res")
        assertTrue("Android resources directory was not found", resourceRoot.isDirectory)

        val defaultStrings = readStringNames(File(resourceRoot, "values/strings.xml"))
        val supportedLocales = listOf(
            "values-bg",
            "values-de",
            "values-fr",
            "values-hi",
            "values-it",
            "values-ja",
            "values-pl",
            "values-ru",
            "values-zh",
        )

        supportedLocales.forEach { localeDirectory ->
            val localizedStrings = readStringNames(
                File(resourceRoot, "$localeDirectory/strings.xml")
            )
            assertEquals(
                "String resources differ for $localeDirectory",
                defaultStrings,
                localizedStrings,
            )
        }
    }

    private fun readStringNames(file: File): Set<String> {
        assertTrue("Missing string resource file: ${file.path}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")

        return buildSet {
            repeat(nodes.length) { index ->
                val element = nodes.item(index) as Element
                if (element.getAttribute("translatable") != "false") {
                    add(element.getAttribute("name"))
                }
            }
        }
    }
}
