package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal data class DayViewColors(
    val ink: Color,
    val panel: Color,
    val cloud: Color,
    val muted: Color,
    val mint: Color,
    val amber: Color,
    val red: Color,
    val glow: Color,
    val overlay: Color,
)

internal val DarkDayViewColors = DayViewColors(
    ink = Color(0xFF0B0D12),
    panel = Color(0xFF14171E),
    cloud = Color(0xFFF3F1EB),
    muted = Color(0xFF8B909B),
    mint = Color(0xFF78E6BD),
    amber = Color(0xFFFFB86B),
    red = Color(0xFFFF7272),
    glow = Color(0xFF171B22),
    overlay = Color.White,
)

internal val LightDayViewColors = DayViewColors(
    ink = Color(0xFFF4F2EC),
    panel = Color(0xFFFFFFFF),
    cloud = Color(0xFF19201D),
    muted = Color(0xFF68716D),
    mint = Color(0xFF168866),
    amber = Color(0xFFB76218),
    red = Color(0xFFC74646),
    glow = Color(0xFFDCEAE3),
    overlay = Color(0xFF16211D),
)

internal val LocalDayViewColors = staticCompositionLocalOf { DarkDayViewColors }

@Composable
internal fun DayViewTheme(content: @Composable (DayViewColors) -> Unit) {
    val isDark = isSystemInDarkTheme()
    val colors = if (isDark) DarkDayViewColors else LightDayViewColors
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = colors.mint,
            background = colors.ink,
            surface = colors.panel,
            onBackground = colors.cloud,
            onSurface = colors.cloud,
            error = colors.red,
        )
    } else {
        lightColorScheme(
            primary = colors.mint,
            background = colors.ink,
            surface = colors.panel,
            onBackground = colors.cloud,
            onSurface = colors.cloud,
            error = colors.red,
        )
    }
    CompositionLocalProvider(LocalDayViewColors provides colors) {
        MaterialTheme(colorScheme = colorScheme) {
            content(colors)
        }
    }
}
