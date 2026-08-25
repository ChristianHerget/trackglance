@file:Suppress("FunctionNaming")

package io.github.christianherget.trackglance.bridge

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal object TrackGlanceColors {
    val LightBackground = Color(0xFFF4FBF6)
    val LightSurface = Color(0xFFFFFFFF)
    val LightText = Color(0xFF1E293B)
    val LightBorder = Color(0xFFCBD5E1)
    val LightPrimary = Color(0xFF006C4C)

    val DarkBackground = Color(0xFF0F172A)
    val DarkSurface = Color(0xFF1E293B)
    val DarkText = Color(0xFFF1F5F9)
    val DarkBorder = Color(0xFF475569)
    val DarkPrimary = Color(0xFF34D399)
}

internal data class TrackGlanceStatusColors(
    val positive: Color,
    val onPositive: Color,
    val positiveContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val neutral: Color,
    val onNeutral: Color,
    val neutralContainer: Color,
)

internal val LocalTrackGlanceStatusColors =
    staticCompositionLocalOf<TrackGlanceStatusColors> {
        error("TrackGlanceStatusColors are unavailable outside TrackGlanceTheme")
    }

private val lightScheme =
    lightColorScheme(
        primary = TrackGlanceColors.LightPrimary,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD2F8E8),
        onPrimaryContainer = Color(0xFF003829),
        secondary = Color(0xFF475569),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE2E8F0),
        onSecondaryContainer = TrackGlanceColors.LightText,
        background = TrackGlanceColors.LightBackground,
        onBackground = TrackGlanceColors.LightText,
        surface = TrackGlanceColors.LightSurface,
        onSurface = TrackGlanceColors.LightText,
        surfaceVariant = Color(0xFFE8F2EC),
        onSurfaceVariant = Color(0xFF475569),
        outline = TrackGlanceColors.LightBorder,
        error = Color(0xFFB42318),
        onError = Color.White,
        errorContainer = Color(0xFFFEE4E2),
        onErrorContainer = Color(0xFF5F1712),
    )

private val darkScheme =
    darkColorScheme(
        primary = TrackGlanceColors.DarkPrimary,
        onPrimary = Color(0xFF003829),
        primaryContainer = Color(0xFF00513A),
        onPrimaryContainer = Color(0xFFA7F3D0),
        secondary = Color(0xFFCBD5E1),
        onSecondary = TrackGlanceColors.DarkSurface,
        secondaryContainer = Color(0xFF334155),
        onSecondaryContainer = TrackGlanceColors.DarkText,
        background = TrackGlanceColors.DarkBackground,
        onBackground = TrackGlanceColors.DarkText,
        surface = TrackGlanceColors.DarkSurface,
        onSurface = TrackGlanceColors.DarkText,
        surfaceVariant = Color(0xFF334155),
        onSurfaceVariant = Color(0xFFCBD5E1),
        outline = TrackGlanceColors.DarkBorder,
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF5F1712),
        onErrorContainer = Color(0xFFFFDAD6),
    )

private val lightStatusColors =
    TrackGlanceStatusColors(
        positive = TrackGlanceColors.LightPrimary,
        onPositive = Color(0xFF003829),
        positiveContainer = Color(0xFFD2F8E8),
        warning = Color(0xFF8A4B08),
        onWarning = Color(0xFF5B3000),
        warningContainer = Color(0xFFFFE2B8),
        error = Color(0xFFB42318),
        onError = Color(0xFF5F1712),
        errorContainer = Color(0xFFFEE4E2),
        neutral = Color(0xFF475569),
        onNeutral = TrackGlanceColors.LightText,
        neutralContainer = Color(0xFFE2E8F0),
    )

private val darkStatusColors =
    TrackGlanceStatusColors(
        positive = TrackGlanceColors.DarkPrimary,
        onPositive = Color(0xFFA7F3D0),
        positiveContainer = Color(0xFF00513A),
        warning = Color(0xFFFEC84B),
        onWarning = Color(0xFFFFE2B8),
        warningContainer = Color(0xFF5B3000),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFFFFDAD6),
        errorContainer = Color(0xFF5F1712),
        neutral = Color(0xFFCBD5E1),
        onNeutral = TrackGlanceColors.DarkText,
        neutralContainer = Color(0xFF334155),
    )

private val trackGlanceShapes = Shapes(medium = RoundedCornerShape(12.dp))

@Composable
internal fun TrackGlanceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTrackGlanceStatusColors provides if (darkTheme) darkStatusColors else lightStatusColors
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme else lightScheme,
            typography = Typography(),
            shapes = trackGlanceShapes,
            content = content,
        )
    }
}
