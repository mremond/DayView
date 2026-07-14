package fr.dayview.app

import androidx.compose.runtime.Composable

/**
 * The smallest-dimension width (in dp) the layout is designed to look right at — a typical
 * phone. Canvases whose smaller side is larger than this render the whole UI proportionally
 * bigger, so a physically large tablet (e.g. a Supernote e-ink) viewed at reading distance
 * shows the UI comfortably large instead of at phone size.
 */
internal const val DISPLAY_SCALE_REFERENCE_DP = 560f

/** Upper bound on the automatic display zoom, so an extreme canvas cannot balloon the UI. */
internal const val DISPLAY_SCALE_MAX = 1.5f

/** Minimum canvas dimensions for the two-column Today dashboard at the default text size. */
internal const val TODAY_WIDE_MIN_WIDTH_DP = 780f
internal const val TODAY_WIDE_MIN_HEIGHT_DP = 760f

/**
 * The two-column dashboard only works when both dimensions can carry it. Enlarged text raises
 * the height budget; otherwise a wide landscape phone or a short desktop window would select a
 * layout whose left column cannot scroll. The one-column fallback is vertically scrollable.
 */
internal fun useWideTodayLayout(widthDp: Float, heightDp: Float, fontScale: Float): Boolean {
    val safeFontScale = fontScale.coerceIn(1f, DISPLAY_SCALE_MAX)
    return widthDp >= TODAY_WIDE_MIN_WIDTH_DP &&
        heightDp >= TODAY_WIDE_MIN_HEIGHT_DP * safeFontScale
}

/**
 * Below this height the mini window preserves the live ring and Focus controls, but drops the
 * secondary goal card. The allowance grows with the text preference so labels never collide.
 */
internal fun showGoalInMiniWindow(heightDp: Float, fontScale: Float): Boolean {
    val safeFontScale = fontScale.coerceIn(1f, DISPLAY_SCALE_MAX)
    val requiredHeight = 400f + (safeFontScale - 1f) * 160f
    return heightDp >= requiredHeight
}

/**
 * Automatic whole-UI zoom factor derived purely from the available space. It scales the
 * composition density, so the ring, text, and spacing all grow together to fill a large
 * screen; on phone-sized canvases (and where [enabled] is false, e.g. desktop) it is a
 * no-op. The result is clamped to `[1, DISPLAY_SCALE_MAX]`.
 */
internal fun autoDisplayScale(minDimensionDp: Float, enabled: Boolean): Float {
    if (!enabled || minDimensionDp <= 0f) return 1f
    return (minDimensionDp / DISPLAY_SCALE_REFERENCE_DP).coerceIn(1f, DISPLAY_SCALE_MAX)
}

/**
 * Whether automatic display scaling applies on this platform. Touch platforms (Android,
 * including e-ink tablets) opt in; desktop returns false because its density is already
 * correct and its windows are freely resizable.
 */
internal expect fun platformAutoScaleEnabled(): Boolean

/**
 * The smaller screen side (in dp) that [autoDisplayScale] should be measured against, chosen
 * so it stays stable while the soft keyboard is open. The live [liveMinDimensionDp] from the
 * layout constraints shrinks when an IME resize (`adjustResize`) squeezes the window, which
 * would otherwise re-scale the whole UI — and drop the focused field's keyboard — the instant
 * a text field is tapped on a large canvas. Platforms that can report a keyboard-independent
 * screen size override it; others fall back to the live value.
 */
@Composable
internal expect fun stableScaleMinDimensionDp(liveMinDimensionDp: Float): Float
