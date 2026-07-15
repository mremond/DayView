package fr.dayview.app

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val InkDark = Color(0xFF0B0D12)
private val InkLight = Color(0xFFF4F2EC)

@Composable
actual fun PlatformThemeChrome(isDark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val activity = view.context as? Activity ?: return
    val window = activity.window
    val ink = (if (isDark) InkDark else InkLight).toArgb()
    SideEffect {
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = ink
            window.navigationBarColor = ink
        }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
    }
}
