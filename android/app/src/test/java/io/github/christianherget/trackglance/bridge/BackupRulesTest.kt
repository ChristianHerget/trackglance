package io.github.christianherget.trackglance.bridge

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class BackupRulesTest {
    @Test
    fun manifestDisablesBackupAndReferencesBothRuleFormats() {
        val application =
            document("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0)
                as Element

        assertEquals("false", application.getAttributeNS(ANDROID_NAMESPACE, "allowBackup"))
        assertEquals(
            "@xml/backup_rules",
            application.getAttributeNS(ANDROID_NAMESPACE, "fullBackupContent"),
        )
        assertEquals(
            "@xml/data_extraction_rules",
            application.getAttributeNS(ANDROID_NAMESPACE, "dataExtractionRules"),
        )
    }

    @Test
    fun legacyRulesExcludeEveryAppDataDomain() {
        val root = document("src/main/res/xml/backup_rules.xml").documentElement

        assertEquals("full-backup-content", root.tagName)
        assertEquals(listOf("exclude"), childElementNames(root).distinct())
        assertDenyAllDomains(root)
    }

    @Test
    fun modernRulesExcludeEveryDomainFromCloudAndDeviceTransfer() {
        val root = document("src/main/res/xml/data_extraction_rules.xml").documentElement

        assertEquals("data-extraction-rules", root.tagName)
        assertEquals(
            setOf("cloud-backup", "device-transfer"),
            childElementNames(root).toSet(),
        )
        assertFalse(childElementNames(root).contains("cross-platform-transfer"))
        assertDenyAllDomains(singleElement(root, "cloud-backup"))
        assertDenyAllDomains(singleElement(root, "device-transfer"))
    }

    private fun assertDenyAllDomains(section: Element) {
        assertEquals(0, section.getElementsByTagName("include").length)
        val excludes = section.getElementsByTagName("exclude")
        val pathsByDomain =
            (0 until excludes.length).associate { index ->
                val exclude = excludes.item(index) as Element
                exclude.getAttribute("domain") to exclude.getAttribute("path")
            }

        assertEquals(ALL_BACKUP_DOMAINS.size, excludes.length)
        assertEquals(ALL_BACKUP_DOMAINS, pathsByDomain.keys)
        assertEquals(setOf("."), pathsByDomain.values.toSet())
    }

    private fun singleElement(parent: Element, tagName: String): Element {
        val elements = parent.getElementsByTagName(tagName)
        assertEquals(1, elements.length)
        return elements.item(0) as Element
    }

    private fun childElementNames(parent: Element): List<String> =
        (0 until parent.childNodes.length).mapNotNull { index ->
            (parent.childNodes.item(index) as? Element)?.tagName
        }

    private fun document(path: String): Document =
        DocumentBuilderFactory.newInstance().run {
            isNamespaceAware = true
            newDocumentBuilder().parse(File(path))
        }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        val ALL_BACKUP_DOMAINS =
            setOf(
                "root",
                "file",
                "database",
                "sharedpref",
                "external",
                "device_root",
                "device_file",
                "device_database",
                "device_sharedpref",
            )
    }
}
