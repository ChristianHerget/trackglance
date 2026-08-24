package io.github.christianherget.trackglance.bridge

import io.github.christianherget.trackglance.bridge.core.BridgeFailure
import io.github.christianherget.trackglance.bridge.core.BridgeFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalizationResourcesTest {
    @Test fun englishAndGermanCatalogsAreCompleteAndManifestLabelsAreResources() {
        val english = resourceNames(File("src/main/res/values/strings.xml").readText())
        val german = resourceNames(File("src/main/res/values-de/strings.xml").readText())
        assertEquals(english, german)
        assertTrue(english.isNotEmpty())
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertEquals(2, Regex("android:label=\"@string/app_name\"").findAll(manifest).count())
        assertFalse(english.contains("launcher_name"))
        assertFalse(manifest.contains("@string/launcher_name"))
    }

    @Test fun typedFailuresRetainRawTechnicalDetailSeparately() {
        val failure = BridgeFailure(
            BridgeFailureKind.LOCUS_PROFILE_QUERY_FAILED,
            "Third-party exception detail",
        )
        assertEquals(BridgeFailureKind.LOCUS_PROFILE_QUERY_FAILED, failure.kind)
        assertEquals("Third-party exception detail", failure.technicalDetail)
    }

    private fun resourceNames(xml: String) = Regex("<string name=\"([^\"]+)\"")
        .findAll(xml).map { it.groupValues[1] }.toSet()
}
