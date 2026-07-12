package fr.dayview.app

/**
 * The smallest-dimension width (in dp) the layout is designed to look right at — a typical
 * phone. Canvases whose smaller side is larger than this render the whole UI proportionally
 * bigger, so a large or high-density e-ink tablet (e.g. Supernote) that under-reports its
 * density does not leave the UI physically tiny.
 */
internal const val DISPLAY_SCALE_REFERENCE_DP = 480f

/** Upper bound on the automatic display zoom, so an extreme canvas cannot balloon the UI. */
internal const val DISPLAY_SCALE_MAX = 1.6f

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
