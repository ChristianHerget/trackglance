package io.github.christianherget.trackglance.bridge

import io.github.christianherget.trackglance.bridge.core.BridgeFailure
import io.github.christianherget.trackglance.bridge.core.BridgeFailureKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationResourcesTest {
    @Test
    fun everySupportedCatalogIsCompleteAndManifestLabelsAreResources() {
        val english = resourceNames(File("src/main/res/values/strings.xml").readText())
        listOf(
                "values-de",
                "values-fr",
                "values-es",
                "values-it",
                "values-pt-rPT",
                "values-zh-rCN",
                "values-zh-rTW",
            )
            .forEach { directory ->
                assertEquals(
                    directory,
                    english,
                    resourceNames(File("src/main/res/$directory/strings.xml").readText()),
                )
            }
        assertTrue(english.isNotEmpty())
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertEquals(1, Regex("android:label=\"@string/app_name\"").findAll(manifest).count())
        assertFalse(english.contains("launcher_name"))
        assertFalse(manifest.contains("@string/launcher_name"))
    }

    @Test
    fun typedFailuresRetainRawTechnicalDetailSeparately() {
        val failure =
            BridgeFailure(
                BridgeFailureKind.LOCUS_PROFILE_QUERY_FAILED,
                "Third-party exception detail",
            )
        assertEquals(BridgeFailureKind.LOCUS_PROFILE_QUERY_FAILED, failure.kind)
        assertEquals("Third-party exception detail", failure.technicalDetail)
    }

    private fun resourceNames(xml: String) =
        Regex("<string name=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toSet()
}
