package fr.dayview.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

internal actual fun platformAutoScaleEnabled(): Boolean = true

/**
 * `Configuration.screen{Width,Height}Dp` describe the app's available screen space and are not
 * reduced by the soft keyboard (unlike the `adjustResize`-squeezed layout constraints), so the
 * display scale stays put while a text field is being edited.
 */
@Composable
internal actual fun stableScaleMinDimensionDp(liveMinDimensionDp: Float): Float {
    val configuration = LocalConfiguration.current
    return minOf(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
}
