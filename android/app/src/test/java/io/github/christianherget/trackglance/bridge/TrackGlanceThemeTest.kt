package io.github.christianherget.trackglance.bridge

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackGlanceThemeTest {
    @Test
    fun canonicalBrandTokensRemainExact() {
        assertEquals(Color(0xFFF4FBF6), TrackGlanceColors.LightBackground)
        assertEquals(Color.White, TrackGlanceColors.LightSurface)
        assertEquals(Color(0xFF1E293B), TrackGlanceColors.LightText)
        assertEquals(Color(0xFFCBD5E1), TrackGlanceColors.LightBorder)
        assertEquals(Color(0xFF006C4C), TrackGlanceColors.LightPrimary)
        assertEquals(Color(0xFF0F172A), TrackGlanceColors.DarkBackground)
        assertEquals(Color(0xFF1E293B), TrackGlanceColors.DarkSurface)
        assertEquals(Color(0xFFF1F5F9), TrackGlanceColors.DarkText)
        assertEquals(Color(0xFF475569), TrackGlanceColors.DarkBorder)
        assertEquals(Color(0xFF34D399), TrackGlanceColors.DarkPrimary)
    }

    @Test
    fun coreTextAndActionPairsMeetWcagAaContrast() {
        assertTrue(contrast(TrackGlanceColors.LightText, TrackGlanceColors.LightBackground) >= 4.5)
        assertTrue(contrast(TrackGlanceColors.LightText, TrackGlanceColors.LightSurface) >= 4.5)
        assertTrue(contrast(Color.White, TrackGlanceColors.LightPrimary) >= 4.5)
        assertTrue(contrast(TrackGlanceColors.DarkText, TrackGlanceColors.DarkBackground) >= 4.5)
        assertTrue(contrast(TrackGlanceColors.DarkText, TrackGlanceColors.DarkSurface) >= 4.5)
        assertTrue(contrast(Color(0xFF003829), TrackGlanceColors.DarkPrimary) >= 4.5)
    }

    private fun contrast(first: Color, second: Color): Double {
        val firstLuminance = first.luminance().toDouble()
        val secondLuminance = second.luminance().toDouble()
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (min(firstLuminance, secondLuminance) + 0.05)
    }
}
