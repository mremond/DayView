package fr.dayview.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

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
