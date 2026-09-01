package io.github.christianherget.trackglance.bridge

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class LocusFunctionIconResourcesTest {
    @Test
    fun locusFilterOwnsDedicatedIconWhileLauncherInheritsApplicationIcon() {
        val manifest = document("src/main/AndroidManifest.xml")
        val application = manifest.getElementsByTagName("application").item(0) as Element
        val activity = manifest.getElementsByTagName("activity").item(0) as Element
        val filters = activity.getElementsByTagName("intent-filter")
        val locusFilter =
            (0 until filters.length)
                .map { filters.item(it) as Element }
                .single { it.actionNames().contains(LOCUS_ACTION) }
        val launcherFilter =
            (0 until filters.length)
                .map { filters.item(it) as Element }
                .single { it.actionNames().contains(LAUNCHER_ACTION) }

        assertEquals("@mipmap/ic_launcher", application.androidAttribute("icon"))
        assertFalse(activity.hasAttributeNS(ANDROID_NAMESPACE, "icon"))
        assertEquals("@drawable/ic_locus_function", locusFilter.androidAttribute("icon"))
        assertFalse(launcherFilter.hasAttributeNS(ANDROID_NAMESPACE, "icon"))
    }

    @Test
    fun dedicatedIconIsNonAdaptiveAndAlwaysUsesTheHighContrastLightMark() {
        val vector = document("src/main/res/drawable/ic_locus_function.xml").documentElement
        val lightColors = colors("src/main/res/values/colors.xml")
        val nightColors = colors("src/main/res/values-night/colors.xml")

        assertEquals("vector", vector.tagName)
        assertTrue(vector.getElementsByTagName("path").length >= 7)
        assertEquals("#F1F5F9", lightColors.getValue("locus_function_icon"))
        assertEquals("#1E293B", lightColors.getValue("locus_function_icon_contrast"))
        assertFalse(nightColors.containsKey("locus_function_icon"))
        assertFalse(nightColors.containsKey("locus_function_icon_contrast"))
    }

    private fun Element.actionNames(): List<String> {
        val actions = getElementsByTagName("action")
        return (0 until actions.length).map {
            (actions.item(it) as Element).androidAttribute("name")
        }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private fun colors(path: String): Map<String, String> {
        val elements = document(path).getElementsByTagName("color")
        return (0 until elements.length).associate { index ->
            val color = elements.item(index) as Element
            color.getAttribute("name") to color.textContent.trim()
        }
    }

    private fun document(path: String): Document =
        DocumentBuilderFactory.newInstance().run {
            isNamespaceAware = true
            newDocumentBuilder().parse(File(path))
        }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val LAUNCHER_ACTION = "android.intent.action.MAIN"
        const val LOCUS_ACTION = "locus.api.android.INTENT_ITEM_MAIN_FUNCTION"
    }
}
