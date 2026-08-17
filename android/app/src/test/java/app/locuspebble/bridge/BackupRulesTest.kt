package app.locuspebble.bridge

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class BackupRulesTest {
    @Test fun authorizationAndSafetyStoresAreExcludedFromEveryAndroidBackupPath() {
        val manifest = parse(mainFile("AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals(
            "@xml/backup_rules",
            application.getAttributeNS(ANDROID_NAMESPACE, "fullBackupContent"),
        )
        assertEquals(
            "@xml/data_extraction_rules",
            application.getAttributeNS(ANDROID_NAMESPACE, "dataExtractionRules"),
        )

        val legacy = parse(mainFile("res/xml/backup_rules.xml"))
        assertEquals(SENSITIVE_STORES, exclusions(legacy.documentElement))

        val modern = parse(mainFile("res/xml/data_extraction_rules.xml"))
        listOf("cloud-backup", "device-transfer").forEach { sectionName ->
            val section = modern.getElementsByTagName(sectionName).item(0) as Element
            assertEquals(SENSITIVE_STORES, exclusions(section))
        }
    }

    private fun exclusions(parent: Element): Set<String> {
        val values = mutableSetOf<String>()
        val nodes = parent.getElementsByTagName("exclude")
        repeat(nodes.length) { index ->
            val exclude = nodes.item(index) as Element
            if (exclude.getAttribute("domain") == "sharedpref") {
                values += exclude.getAttribute("path")
            }
        }
        return values
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(file)

    private fun mainFile(relative: String): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            listOf(
                File(directory, "src/main/$relative"),
                File(directory, "android/app/src/main/$relative"),
            ).firstOrNull(File::isFile)?.let { return it }
            directory = directory.parentFile
        }
        error("Could not locate Android main source file: $relative")
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        val SENSITIVE_STORES = setOf(
            "command_journal.xml",
            "core_app_trust.xml",
            "snapshot_delivery_epoch.xml",
            "profile_transfer_serial.xml",
        )
    }
}
