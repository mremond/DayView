package fr.dayview.app

/**
 * The smallest-dimension width (in dp) the layout is designed to look right at — a typical
 * phone. Canvases whose smaller side is larger than this render the whole UI proportionally
 * bigger, so a physically large tablet (e.g. a Supernote e-ink) viewed at reading distance
 * shows the UI comfortably large instead of at phone size.
 */
internal const val DISPLAY_SCALE_REFERENCE_DP = 560f

/** Upper bound on the automatic display zoom, so an extreme canvas cannot balloon the UI. */
internal const val DISPLAY_SCALE_MAX = 1.5f

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
